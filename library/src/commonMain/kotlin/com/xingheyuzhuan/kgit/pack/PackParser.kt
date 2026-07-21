package com.xingheyuzhuan.kgit.pack

import com.xingheyuzhuan.kgit.model.*
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Source
import okio.inflate

class PackParser(private val packBytes: ByteArray) {

    private data class RawObject(
        val type: ObjectType,
        val data: ByteArray,
        val offset: Int,
        var baseOffset: Int? = null,
        var baseSha1: String? = null
    )

    fun parse(): List<GitObject> {
        val source = Buffer().write(packBytes)
        val startBytesSize = source.size

        // 1. 校验魔数 "PACK"
        val packMagic = source.readUtf8(4)
        if (packMagic != "PACK") {
            error("Invalid Packfile header: $packMagic")
        }

        // 2. 读取版本号 (4 字节) 和对象总数 (4 字节)
        val version = source.readInt()
        if (version != 2) {
            error("Unsupported Packfile version: $version")
        }
        val objectCount = source.readInt()

        val rawObjects = mutableListOf<RawObject>()
        val offsetMap = mutableMapOf<Int, RawObject>()

        // 3. 逐个流式解析原始二进制对象
        val checksumSize = 20L
        for (i in 0 until objectCount) {
            if (source.size <= checksumSize) {
                break
            }

            val objectOffset = (startBytesSize - source.size).toInt()
            val (typeInt, _) = readObjectTypeAndSize(source)

            when (typeInt) {
                1, 2, 3, 4 -> { // COMMIT, TREE, BLOB, TAG
                    val type = ObjectType.fromValue(typeInt)!!
                    val decompressed = decompressZlib(source)
                    val rawObj = RawObject(type, decompressed, objectOffset)
                    rawObjects.add(rawObj)
                    offsetMap[objectOffset] = rawObj
                }
                6 -> { // OFS_DELTA
                    val baseOffsetDelta = readOffsetVarint(source)
                    val baseOffset = objectOffset - baseOffsetDelta
                    val decompressed = decompressZlib(source)
                    val rawObj = RawObject(
                        type = ObjectType.OFS_DELTA,
                        data = decompressed,
                        offset = objectOffset,
                        baseOffset = baseOffset
                    )
                    rawObjects.add(rawObj)
                    offsetMap[objectOffset] = rawObj
                }
                7 -> { // REF_DELTA
                    val baseSha1Bytes = source.readByteArray(20)
                    val baseSha1 = baseSha1Bytes.toHexString()
                    val decompressed = decompressZlib(source)
                    val rawObj = RawObject(
                        type = ObjectType.REF_DELTA,
                        data = decompressed,
                        offset = objectOffset,
                        baseSha1 = baseSha1
                    )
                    rawObjects.add(rawObj)
                    offsetMap[objectOffset] = rawObj
                }
                else -> error("Unknown Git object type: $typeInt")
            }
        }

        // 4. 校验 Packfile 文件尾部 20 字节 SHA-1 校验和
        if (packBytes.size >= 20) {
            val expectedChecksum = packBytes.copyOfRange(packBytes.size - 20, packBytes.size).toHexString()
            val actualChecksum = packBytes.toByteString(0, packBytes.size - 20).sha1().hex()
            if (expectedChecksum != actualChecksum) {
                error("Packfile integrity check failed! Expected: $expectedChecksum, Actual: $actualChecksum")
            }
        } else {
            error("Invalid Packfile length: missing 20-byte checksum trailer")
        }

        // 5. 递归还原 Delta 对象并构建 SHA-1 动态索引池
        val resolvedByOffset = mutableMapOf<Int, GitObject>()
        val sha1Map = mutableMapOf<String, GitObject>()

        fun resolveRawObject(raw: RawObject): GitObject {
            resolvedByOffset[raw.offset]?.let { return it }

            val finalObject = when (raw.type) {
                ObjectType.COMMIT, ObjectType.TREE, ObjectType.BLOB, ObjectType.TAG -> {
                    createGitObject(raw.type, raw.data)
                }
                ObjectType.OFS_DELTA -> {
                    val parentRaw = offsetMap[raw.baseOffset]
                        ?: error("OFS_DELTA base object offset not found: ${raw.baseOffset}")
                    val parentGitObj = resolveRawObject(parentRaw)
                    val restoredBytes = Delta.applyDelta(parentGitObj.rawData, raw.data)
                    createGitObject(parentGitObj.type, restoredBytes)
                }
                ObjectType.REF_DELTA -> {
                    val targetSha1 = raw.baseSha1!!
                    var parentGitObj = sha1Map[targetSha1]

                    // 若缓存未命中，在所有对象中动态解析匹配（支持基准对象本身也是 Delta 的场景）
                    if (parentGitObj == null) {
                        for (candidate in rawObjects) {
                            if (candidate.offset == raw.offset) continue
                            val resolvedCandidate = resolveRawObject(candidate)
                            if (resolvedCandidate.sha1 == targetSha1) {
                                parentGitObj = resolvedCandidate
                                break
                            }
                        }
                    }

                    val parent = parentGitObj
                        ?: error("REF_DELTA base SHA-1 not found: $targetSha1")

                    val restoredBytes = Delta.applyDelta(parent.rawData, raw.data)
                    createGitObject(parent.type, restoredBytes)
                }
            }

            resolvedByOffset[raw.offset] = finalObject
            sha1Map[finalObject.sha1] = finalObject
            return finalObject
        }

        // 第一阶段：优先解析非 REF_DELTA 对象（Base 对象及 OFS_DELTA），填充 sha1Map 索引池
        for (raw in rawObjects) {
            if (raw.type != ObjectType.REF_DELTA) {
                resolveRawObject(raw)
            }
        }

        // 第二阶段：全量还原所有对象
        return rawObjects.map { resolveRawObject(it) }
    }

    private fun readObjectTypeAndSize(source: okio.BufferedSource): Pair<Int, Long> {
        var b = source.readByte().toInt() and 0xFF
        val type = (b shr 4) and 0x07
        var size = (b and 0x0F).toLong()
        var shift = 4

        while ((b and 0x80) != 0) {
            b = source.readByte().toInt() and 0xFF
            size = size or ((b and 0x7F).toLong() shl shift)
            shift += 7
        }
        return Pair(type, size)
    }

    private fun readOffsetVarint(source: okio.BufferedSource): Int {
        var b = source.readByte().toInt() and 0xFF
        var offset = b and 0x7F
        while ((b and 0x80) != 0) {
            b = source.readByte().toInt() and 0xFF
            offset = ((offset + 1) shl 7) or (b and 0x7F)
        }
        return offset
    }

    private fun decompressZlib(source: okio.BufferedSource): ByteArray {
        val oneByteSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (source.exhausted()) return -1L
                return source.read(sink, minOf(byteCount, 1L))
            }
            override fun timeout() = source.timeout()
            override fun close() {}
        }

        val inflaterSource = oneByteSource.inflate()
        val resultBuffer = Buffer()
        try {
            resultBuffer.writeAll(inflaterSource)
        } finally {
            inflaterSource.close()
        }
        return resultBuffer.readByteArray()
    }

    private fun createGitObject(type: ObjectType, rawData: ByteArray): GitObject {
        val sha1 = calculateGitSha1(type.typeStr, rawData)
        return when (type) {
            ObjectType.COMMIT -> parseCommitObject(sha1, rawData)
            ObjectType.TREE -> parseTreeObject(sha1, rawData)
            ObjectType.BLOB, ObjectType.TAG -> BlobObject(sha1, rawData)
            else -> error("Cannot construct GitObject of type $type")
        }
    }

    private fun parseCommitObject(sha1: String, rawData: ByteArray): CommitObject {
        val content = rawData.decodeToString()
        val lines = content.lines()
        var treeHash = ""
        val parentHashes = mutableListOf<String>()
        var author = ""
        val messageBuilder = StringBuilder()
        var isMessageHeader = false

        for (line in lines) {
            if (isMessageHeader) {
                messageBuilder.append(line).append("\n")
                continue
            }
            if (line.isEmpty()) {
                isMessageHeader = true
                continue
            }
            when {
                line.startsWith("tree ") -> treeHash = line.removePrefix("tree ").trim()
                line.startsWith("parent ") -> parentHashes.add(line.removePrefix("parent ").trim())
                line.startsWith("author ") -> author = line.removePrefix("author ").trim()
            }
        }

        return CommitObject(
            sha1 = sha1,
            rawData = rawData,
            treeHash = treeHash,
            parentHashes = parentHashes,
            author = author,
            message = messageBuilder.toString().trim()
        )
    }

    private fun parseTreeObject(sha1: String, rawData: ByteArray): TreeObject {
        val entries = mutableListOf<TreeEntry>()
        var cursor = 0

        while (cursor < rawData.size) {
            var nullIndex = cursor
            while (nullIndex < rawData.size && rawData[nullIndex] != 0.toByte()) {
                nullIndex++
            }
            if (nullIndex >= rawData.size) break

            val modeAndName = rawData.copyOfRange(cursor, nullIndex).decodeToString()
            val parts = modeAndName.split(' ', limit = 2)
            val mode = parts[0]
            val name = parts[1]

            cursor = nullIndex + 1
            val entrySha1Bytes = rawData.copyOfRange(cursor, cursor + 20)
            val entrySha1 = entrySha1Bytes.toHexString()
            cursor += 20

            entries.add(TreeEntry(mode, name, entrySha1))
        }

        return TreeObject(sha1, rawData, entries)
    }

    private fun ByteArray.toHexString(): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(size * 2)
        for (byte in this) {
            val i = byte.toInt() and 0xFF
            result.append(hexChars[i shr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }
}