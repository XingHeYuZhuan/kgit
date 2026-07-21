package com.xingheyuzhuan.kgit.ext

import com.xingheyuzhuan.kgit.checkout.TreeCheckout
import com.xingheyuzhuan.kgit.logging.ProgressMonitor
import com.xingheyuzhuan.kgit.model.CommitObject
import com.xingheyuzhuan.kgit.network.GitHttpClient
import com.xingheyuzhuan.kgit.pack.PackParser
import com.xingheyuzhuan.kgit.storage.ObjectStore
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

class RepoDownloadCommand {
    private var uri: String = ""
    private var directory: Path? = null
    private var branch: String? = null
    private var token: String? = null
    private var httpClient: GitHttpClient = GitHttpClient()
    private var progressMonitor: ProgressMonitor = ProgressMonitor.DEFAULT

    fun setUri(uri: String) = apply { this.uri = uri }
    fun setDirectory(dir: Path) = apply { this.directory = dir }
    fun setBranch(branch: String) = apply { this.branch = branch }
    fun setToken(token: String) = apply { this.token = token }
    fun setHttpClient(client: GitHttpClient) = apply { this.httpClient = client }
    fun setProgressMonitor(monitor: ProgressMonitor) = apply { this.progressMonitor = monitor }

    suspend fun call(fileSystem: FileSystem = FileSystem.SYSTEM): Path {
        require(uri.isNotEmpty()) { "Repository URI cannot be empty" }

        val targetDir = directory ?: deriveDirectoryFromUrl(uri).toPath()

        // 1. 获取远程 Refs
        progressMonitor.beginTask("Discovering remote refs", ProgressMonitor.UNKNOWN)
        val discoveryResult = httpClient.discoverRemoteRefs(repoUrl = uri, token = token)
        require(discoveryResult.refs.isNotEmpty()) { "Remote repository is empty or does not exist" }
        progressMonitor.endTask()

        // 2. 解析目标分支的 Commit SHA-1
        progressMonitor.beginTask("Resolving target branch", ProgressMonitor.UNKNOWN)
        val targetCommitHash = resolveTargetHash(discoveryResult, branch)
        progressMonitor.endTask()

        // 3. 下载并解析 Packfile 到内存
        progressMonitor.beginTask("Downloading packfile", ProgressMonitor.UNKNOWN)
        val packBytes = httpClient.downloadPackfile(
            repoUrl = uri,
            targetHash = targetCommitHash,
            token = token
        )
        progressMonitor.endTask()

        progressMonitor.beginTask("Parsing packfile objects", ProgressMonitor.UNKNOWN)
        val gitObjects = PackParser(packBytes).parse()
        val store = ObjectStore(gitObjects)
        progressMonitor.endTask()

        // 4. 纯工作区检出（完全跳过 .git 目录与元数据的生成）
        val commitObject = store.getAs<CommitObject>(targetCommitHash)
            ?: error("Commit object not found in packfile: $targetCommitHash")

        progressMonitor.beginTask("Checking out working tree", ProgressMonitor.UNKNOWN)
        TreeCheckout.checkoutTree(
            store = store,
            treeSha1 = commitObject.treeHash,
            targetDir = targetDir,
            fileSystem = fileSystem
        )
        progressMonitor.endTask()

        return targetDir
    }

    private fun deriveDirectoryFromUrl(url: String): String {
        val cleanUrl = url.trimEnd('/').removeSuffix(".git")
        return cleanUrl.substringAfterLast('/')
    }

    private fun resolveTargetHash(
        discovery: com.xingheyuzhuan.kgit.network.RemoteDiscoveryResult,
        specifiedBranch: String?
    ): String {
        val refMap = discovery.refs.associate { it.refName to it.commitHash }
        if (!specifiedBranch.isNullOrEmpty()) {
            val fullRef = if (specifiedBranch.startsWith("refs/heads/")) specifiedBranch else "refs/heads/$specifiedBranch"
            return refMap[fullRef] ?: refMap[specifiedBranch]
            ?: error("Specified branch not found in remote repository: $specifiedBranch")
        }
        val defaultRef = discovery.symrefs["HEAD"] ?: "refs/heads/main"
        return refMap[defaultRef] ?: discovery.refs.first().commitHash
    }
}