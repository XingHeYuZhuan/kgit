package com.xingheyuzhuan.kgit.pack

object Delta {
    /**
     * 应用 Git Packfile 的 Delta 字节增量
     * @param baseData 基础对象的字节数组
     * @param deltaData 增量对象的字节数组
     * @return 还原后的完整对象字节数组
     */
    fun applyDelta(baseData: ByteArray, deltaData: ByteArray): ByteArray {
        var pos = 0

        fun readVarint(): Int {
            var shift = 0
            var res = 0
            while (pos < deltaData.size) {
                val byte = deltaData[pos++].toInt() and 0xFF
                res = res or ((byte and 0x7F) shl shift)
                if ((byte and 0x80) == 0) break
                shift += 7
            }
            return res
        }

        // 1. 读取基础对象尺寸 与 目标对象尺寸
        readVarint() // base size
        val targetSize = readVarint() // target size

        // 2. 预分配目标大小的 ByteArray
        val dest = ByteArray(targetSize)
        var destPos = 0

        while (pos < deltaData.size) {
            val cmd = deltaData[pos++].toInt() and 0xFF
            if ((cmd and 0x80) != 0) {
                // Copy 操作：从 baseData 拷贝数据
                var offset = 0
                var size = 0

                if ((cmd and 0x01) != 0) offset = offset or (deltaData[pos++].toInt() and 0xFF)
                if ((cmd and 0x02) != 0) offset = offset or ((deltaData[pos++].toInt() and 0xFF) shl 8)
                if ((cmd and 0x04) != 0) offset = offset or ((deltaData[pos++].toInt() and 0xFF) shl 16)
                if ((cmd and 0x08) != 0) offset = offset or ((deltaData[pos++].toInt() and 0xFF) shl 24)

                if ((cmd and 0x10) != 0) size = size or (deltaData[pos++].toInt() and 0xFF)
                if ((cmd and 0x20) != 0) size = size or ((deltaData[pos++].toInt() and 0xFF) shl 8)
                if ((cmd and 0x40) != 0) size = size or ((deltaData[pos++].toInt() and 0xFF) shl 16)

                if (size == 0) size = 0x10000

                // 使用 Kotlin 原生 API 替代 System.arraycopy
                baseData.copyInto(
                    destination = dest,
                    destinationOffset = destPos,
                    startIndex = offset,
                    endIndex = offset + size
                )
                destPos += size
            } else if (cmd > 0) {
                // Insert 操作：直接从 deltaData 插入新数据
                deltaData.copyInto(
                    destination = dest,
                    destinationOffset = destPos,
                    startIndex = pos,
                    endIndex = pos + cmd
                )
                pos += cmd
                destPos += cmd
            }
        }
        return dest
    }
}