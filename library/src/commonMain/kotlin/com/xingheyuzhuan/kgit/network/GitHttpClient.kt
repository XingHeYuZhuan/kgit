package com.xingheyuzhuan.kgit.network

import com.xingheyuzhuan.kgit.logging.ProgressMonitor
import com.xingheyuzhuan.kgit.util.PktLine
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8

data class RemoteRef(
    val refName: String,
    val commitHash: String,
    val isPeeled: Boolean = false
)

data class RemoteDiscoveryResult(
    val refs: List<RemoteRef>,
    val symrefs: Map<String, String>
)

class GitHttpClient(
    private val client: HttpClient = HttpClient()
) {
    /**
     * 服务发现：获取远程仓库所有的 Ref 及其 HEAD 符号链接映射
     */
    suspend fun discoverRemoteRefs(
        repoUrl: String,
        token: String? = null
    ): RemoteDiscoveryResult {
        val cleanUrl = repoUrl.trimEnd('/').removeSuffix(".git")
        val requestUrl = "$cleanUrl.git/info/refs?service=git-upload-pack"

        val response: HttpResponse = client.get(requestUrl) {
            header(HttpHeaders.UserAgent, "git/2.39.0")
            header(HttpHeaders.Accept, "application/x-git-upload-pack-advertisement")
            token?.let {
                val b64 = "git:$it".encodeUtf8().base64()
                header(HttpHeaders.Authorization, "Basic $b64")
            }
        }

        if (response.status != HttpStatusCode.OK) {
            error("Remote ref discovery failed, HTTP status code: ${response.status.value}")
        }

        val bytes = response.body<ByteArray>()
        val pktLines = PktLine.decode(bytes)

        val refList = mutableListOf<RemoteRef>()
        val symrefs = mutableMapOf<String, String>()

        for (line in pktLines) {
            if (line.isEmpty() || line[0] == '#'.code.toByte()) continue

            val lineStr = line.decodeToString().trimEnd('\n')
            val parts = lineStr.split('\u0000')
            val refInfo = parts[0].trim()

            // 解析服务端 Capability 中的 symref 信息 (例如 symref=HEAD:refs/heads/main)
            if (parts.size > 1) {
                val capabilities = parts[1].split(' ')
                for (cap in capabilities) {
                    if (cap.startsWith("symref=")) {
                        val symVal = cap.removePrefix("symref=")
                        val symParts = symVal.split(':', limit = 2)
                        if (symParts.size == 2) {
                            symrefs[symParts[0]] = symParts[1]
                        }
                    }
                }
            }

            val refParts = refInfo.split(' ')
            if (refParts.size >= 2) {
                val hash = refParts[0].trim()
                val refName = refParts[1].trim()

                if (hash.length == 40) {
                    val isPeeled = refName.endsWith("^{}")
                    refList.add(RemoteRef(refName, hash, isPeeled))
                }
            }
        }

        return RemoteDiscoveryResult(refs = refList, symrefs = symrefs)
    }

    /**
     * 下载特定 Commit Hash 的 Packfile 数据流
     */
    suspend fun downloadPackfile(
        repoUrl: String,
        targetHash: String,
        token: String? = null,
        progressMonitor: ProgressMonitor? = null
    ): ByteArray {
        val cleanUrl = repoUrl.trimEnd('/').removeSuffix(".git")
        val requestUrl = "$cleanUrl.git/git-upload-pack"

        val bodyPayload = PktLine.encode("want $targetHash side-band-64k\n") + "0000" + PktLine.encode("done\n")

        val response: HttpResponse = client.post(requestUrl) {
            header(HttpHeaders.UserAgent, "git/2.39.0")
            header(HttpHeaders.ContentType, "application/x-git-upload-pack-request")
            header(HttpHeaders.Accept, "application/x-git-upload-pack-result")
            token?.let {
                val b64 = "git:$it".encodeUtf8().base64()
                header(HttpHeaders.Authorization, "Basic $b64")
            }
            setBody(bodyPayload.encodeToByteArray())
        }

        if (response.status != HttpStatusCode.OK) {
            error("Failed to download packfile, HTTP status code: ${response.status.value}")
        }

        val rawBytes = response.body<ByteArray>()
        val packBuffer = Buffer()
        var cursor = 0

        while (cursor < rawBytes.size) {
            if (cursor + 4 > rawBytes.size) break
            val lenHex = rawBytes.decodeToString(cursor, cursor + 4)
            val lineLen = lenHex.toIntOrNull(16) ?: break
            cursor += 4

            if (lineLen == 0) continue

            val payloadLen = lineLen - 4
            if (payloadLen <= 0 || cursor + payloadLen > rawBytes.size) break

            val bandId = rawBytes[cursor].toInt() and 0xFF
            val payload = rawBytes.copyOfRange(cursor + 1, cursor + payloadLen)
            cursor += payloadLen

            when (bandId) {
                1 -> {
                    packBuffer.write(payload)
                    progressMonitor?.update(payload.size)
                }
                2 -> {
                    val progressText = payload.decodeToString().trimEnd('\n', '\r')
                    if (progressText.isNotBlank()) {
                        progressMonitor?.beginTask(progressText, ProgressMonitor.UNKNOWN)
                    }
                }
                3 -> {
                    error("Git Server Error: ${payload.decodeToString()}")
                }
            }
        }

        val packBytes = packBuffer.readByteArray()
        val packMagic = byteArrayOf(0x50, 0x41, 0x43, 0x4B) // "PACK"
        val packIndex = indexOfSequence(packBytes, packMagic)

        if (packIndex == -1) {
            error("Valid PACK data stream not found in response")
        }

        return packBytes.copyOfRange(packIndex, packBytes.size)
    }

    private fun indexOfSequence(source: ByteArray, target: ByteArray): Int {
        for (i in 0..source.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}