package com.xingheyuzhuan.kgit.git

import com.xingheyuzhuan.kgit.checkout.TreeCheckout
import com.xingheyuzhuan.kgit.logging.ProgressMonitor
import com.xingheyuzhuan.kgit.model.CommitObject
import com.xingheyuzhuan.kgit.model.GitObject
import com.xingheyuzhuan.kgit.network.GitHttpClient
import com.xingheyuzhuan.kgit.pack.PackParser
import com.xingheyuzhuan.kgit.storage.ObjectStore
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.deflate

class CloneCommand {
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

    suspend fun call(fileSystem: FileSystem): GitRepository {
        require(uri.isNotEmpty()) { "Repository URI cannot be empty" }

        // 1. 推导目标落盘路径 (若用户未指定，则从 URL 中解析仓库名称作为目录)
        val targetDir = directory ?: deriveDirectoryFromUrl(uri).toPath()

        // 2. 服务发现：拉取全量 Ref 及符号引用
        progressMonitor.beginTask("Discovering remote refs", ProgressMonitor.UNKNOWN)
        val discoveryResult = httpClient.discoverRemoteRefs(repoUrl = uri, token = token)
        require(discoveryResult.refs.isNotEmpty()) { "Remote repository is empty or does not exist" }
        progressMonitor.endTask()

        // 3. 推导要克隆的目标分支与对应 Commit SHA-1
        progressMonitor.beginTask("Resolving target branch", ProgressMonitor.UNKNOWN)
        val (targetRefName, targetCommitHash) = resolveTargetRef(discoveryResult, branch)
        progressMonitor.endTask()

        // 提取简短分支名 (如 "refs/heads/main" -> "main")
        val shortBranchName = targetRefName.removePrefix("refs/heads/")

        // 4. 下载 Packfile
        progressMonitor.beginTask("Downloading packfile", ProgressMonitor.UNKNOWN)
        val packBytes = httpClient.downloadPackfile(
            repoUrl = uri,
            targetHash = targetCommitHash,
            token = token,
            progressMonitor = progressMonitor
        )
        progressMonitor.endTask()

        // 5. 解析 Packfile 对象
        val gitObjects = PackParser(packBytes, progressMonitor).parse()
        val store = ObjectStore(gitObjects)

        // 6. 初始化标准的 .git 元数据结构并持久化所有 Loose Objects
        progressMonitor.beginTask("Initializing .git metadata", ProgressMonitor.UNKNOWN)
        initGitMetadataDirectory(
            fileSystem = fileSystem,
            targetDir = targetDir,
            branchName = shortBranchName,
            fullRefName = targetRefName,
            commitHash = targetCommitHash,
            repoUri = uri,
            gitObjects = gitObjects
        )
        progressMonitor.endTask()

        // 7. 检出工作区（Working Tree）文件
        val commitObject = store.getAs<CommitObject>(targetCommitHash)
            ?: error("Commit object not found in packfile: $targetCommitHash")

        TreeCheckout.checkoutTree(
            store = store,
            treeSha1 = commitObject.treeHash,
            targetDir = targetDir,
            fileSystem = fileSystem,
            progressMonitor = progressMonitor
        )

        return GitRepository(targetDir, fileSystem)
    }

    /**
     * 根据 URL 解析默认落盘文件夹名
     */
    private fun deriveDirectoryFromUrl(url: String): String {
        val cleanUrl = url.trimEnd('/').removeSuffix(".git")
        val lastSegment = cleanUrl.substringAfterLast('/')
        require(lastSegment.isNotEmpty()) { "Cannot derive directory name from URL: $url" }
        return lastSegment
    }

    /**
     * 确定目标 Ref 及 Hash
     */
    private fun resolveTargetRef(
        discovery: com.xingheyuzhuan.kgit.network.RemoteDiscoveryResult,
        specifiedBranch: String?
    ): Pair<String, String> {
        val refMap = discovery.refs.associate { it.refName to it.commitHash }

        if (!specifiedBranch.isNullOrEmpty()) {
            val fullRef = if (specifiedBranch.startsWith("refs/heads/")) specifiedBranch else "refs/heads/$specifiedBranch"
            val hash = refMap[fullRef] ?: refMap[specifiedBranch]
            ?: error("Specified branch not found in remote repository: $specifiedBranch")
            return Pair(fullRef, hash)
        }

        // 未指定分支时优先选取 symref 指定的默认 HEAD 目标
        discovery.symrefs["HEAD"]?.let { headTarget ->
            refMap[headTarget]?.let { hash ->
                return Pair(headTarget, hash)
            }
        }

        // 退回逻辑：寻找 HEAD 对应 Hash 匹配的 refs/heads/*
        val headHash = refMap["HEAD"]
        if (headHash != null) {
            val matchedRef = discovery.refs.firstOrNull {
                it.refName.startsWith("refs/heads/") && it.commitHash == headHash
            }
            if (matchedRef != null) {
                return Pair(matchedRef.refName, matchedRef.commitHash)
            }
        }

        // 默认保底 main / master
        val defaultRef = refMap.keys.firstOrNull { it == "refs/heads/main" || it == "refs/heads/master" }
            ?: refMap.keys.firstOrNull { it.startsWith("refs/heads/") }
            ?: error("Unable to confirm default branch of remote repository")

        return Pair(defaultRef, refMap[defaultRef]!!)
    }

    /**
     * 构建符合 Git 官方标准的 .git 元数据结构并写入底层对象
     */
    private fun initGitMetadataDirectory(
        fileSystem: FileSystem,
        targetDir: Path,
        branchName: String,
        fullRefName: String,
        commitHash: String,
        repoUri: String,
        gitObjects: List<GitObject>
    ) {
        val gitDir = targetDir / ".git"

        // 1. 建立符合 Git 规范的基础目录结构
        fileSystem.createDirectories(gitDir / "objects" / "info")
        fileSystem.createDirectories(gitDir / "objects" / "pack")
        fileSystem.createDirectories(gitDir / "branches")
        fileSystem.createDirectories(gitDir / "hooks")
        fileSystem.createDirectories(gitDir / "info")
        fileSystem.createDirectories(gitDir / "refs" / "heads")
        fileSystem.createDirectories(gitDir / "refs" / "remotes" / "origin")
        fileSystem.createDirectories(gitDir / "refs" / "tags")

        // 2. 将解析出的所有 Git 对象按 Zlib 压缩格式写入 .git/objects/xx/yyyy... (松散对象格式)
        writeLooseObjects(fileSystem, gitDir, gitObjects)

        // 3. 写入 HEAD 指针 (指向当前本地分支)
        fileSystem.write(gitDir / "HEAD") {
            writeUtf8("ref: refs/heads/$branchName\n")
        }

        // 4. 写入本地分支指针 (.git/refs/heads/<branch>)
        fileSystem.write(gitDir / "refs" / "heads" / branchName) {
            writeUtf8("$commitHash\n")
        }

        // 5. 写入远程追踪分支指针 (.git/refs/remotes/origin/<branch>)
        fileSystem.write(gitDir / "refs" / "remotes" / "origin" / branchName) {
            writeUtf8("$commitHash\n")
        }

        // 6. 写入远程 HEAD 符号指针
        fileSystem.write(gitDir / "refs" / "remotes" / "origin" / "HEAD") {
            writeUtf8("ref: refs/remotes/origin/$branchName\n")
        }

        // 7. 写入标准的 Git 配置文件 (.git/config)
        val configContent = """
            [core]
                repositoryformatversion = 0
                filemode = true
                bare = false
                logallrefupdates = true
                symlinks = false
                ignorecase = true
            [remote "origin"]
                url = $repoUri
                fetch = +refs/heads/*:refs/remotes/origin/*
            [branch "$branchName"]
                remote = origin
                merge = $fullRefName
        """.trimIndent() + "\n"

        fileSystem.write(gitDir / "config") {
            writeUtf8(configContent)
        }

        // 8. 写入辅助的标准描述与排除文件
        fileSystem.write(gitDir / "description") {
            writeUtf8("Unnamed repository; edit this file 'description' to name the repository.\n")
        }
        fileSystem.write(gitDir / "info" / "exclude") {
            writeUtf8("# git ls-files --others --exclude-from=.git/info/exclude\n# Lines that start with '#' are comments.\n# For more information see 'git help gitignore'\n")
        }
    }

    /**
     * 将解包出的对象编码为 Git 官方 Loose Object 格式并使用 Zlib 压缩写入磁盘
     * 格式: zlib("<type> <size>\0<bytes>")
     */
    private fun writeLooseObjects(
        fileSystem: FileSystem,
        gitDir: Path,
        gitObjects: List<GitObject>
    ) {
        for (obj in gitObjects) {
            val dirName = obj.sha1.substring(0, 2)
            val fileName = obj.sha1.substring(2)
            val objectDir = gitDir / "objects" / dirName
            val objectPath = objectDir / fileName

            if (fileSystem.exists(objectPath)) continue

            fileSystem.createDirectories(objectDir)

            // 构建 Loose Object 数据头: "type length\0payload"
            val header = "${obj.type.typeStr} ${obj.rawData.size}\u0000".encodeToByteArray()
            val fullContent = Buffer().apply {
                write(header)
                write(obj.rawData)
            }

            // 使用 Okio 的 deflate 进行 Zlib 压缩落盘
            fileSystem.write(objectPath) {
                val deflateSink = deflate()
                deflateSink.write(fullContent, fullContent.size)
                deflateSink.close()
            }
        }
    }
}