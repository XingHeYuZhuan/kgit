package com.xingheyuzhuan.kgit.util

object PktLine {
    /**
     * 编码为 Git pkt-line 帧字符串
     * - content 为 null 或空串时返回 Flush 帧 "0000"
     */
    fun encode(content: String?): String {
        if (content.isNullOrEmpty()) return "0000"
        val bytes = content.encodeToByteArray()
        val totalLen = bytes.size + 4
        return totalLen.toString(16).padStart(4, '0') + content
    }

    /**
     * 解析二进制 pkt-line 数据帧
     */
    fun decode(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var pos = 0
        while (pos + 4 <= data.size) {
            val hexLen = data.copyOfRange(pos, pos + 4).decodeToString()
            pos += 4

            // "0000" (Flush-pkt) 或 "0001" (Delimiter-pkt) 属于分隔帧
            if (hexLen == "0000" || hexLen == "0001") {
                continue
            }

            val length = hexLen.toIntOrNull(16) ?: break
            if (length < 4) continue

            val payloadSize = length - 4
            if (pos + payloadSize > data.size) break

            val payload = data.copyOfRange(pos, pos + payloadSize)
            pos += payloadSize
            result.add(payload)
        }
        return result
    }
}