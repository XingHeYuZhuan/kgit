package com.xingheyuzhuan.kgit.git

import com.xingheyuzhuan.kgit.network.GitHttpClient
import com.xingheyuzhuan.kgit.network.RemoteRef

class LsRemoteCommand {
    private var uri: String = ""
    private var token: String? = null
    private var headsOnly: Boolean = false
    private var tagsOnly: Boolean = false
    private val patterns = mutableListOf<String>()
    private var httpClient: GitHttpClient = GitHttpClient()

    fun setUri(uri: String) = apply { this.uri = uri }
    fun setToken(token: String) = apply { this.token = token }
    fun setHeadsOnly(headsOnly: Boolean) = apply { this.headsOnly = headsOnly }
    fun setTagsOnly(tagsOnly: Boolean) = apply { this.tagsOnly = tagsOnly }
    fun addPattern(pattern: String) = apply { this.patterns.add(pattern) }
    fun setHttpClient(client: GitHttpClient) = apply { this.httpClient = client }

    /**
     * 执行命令，返回匹配的所有远程引用
     */
    suspend fun call(): List<RemoteRef> {
        require(uri.isNotEmpty()) { "Repository URI cannot be empty" }

        val discoveryResult = httpClient.discoverRemoteRefs(repoUrl = uri, token = token)
        var resultRefs = discoveryResult.refs

        // 1. 根据按类型过滤条件筛选
        if (headsOnly) {
            resultRefs = resultRefs.filter { it.refName.startsWith("refs/heads/") }
        } else if (tagsOnly) {
            resultRefs = resultRefs.filter { it.refName.startsWith("refs/tags/") }
        }

        // 2. 根据模式串匹配 (例如指定分支名或通配符)
        if (patterns.isNotEmpty()) {
            resultRefs = resultRefs.filter { ref ->
                patterns.any { pattern ->
                    ref.refName == pattern ||
                            ref.refName == "refs/heads/$pattern" ||
                            ref.refName == "refs/tags/$pattern" ||
                            ref.refName.endsWith("/$pattern")
                }
            }
        }

        return resultRefs
    }
}