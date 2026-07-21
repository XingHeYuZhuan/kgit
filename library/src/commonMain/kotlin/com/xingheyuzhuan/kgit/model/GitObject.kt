package com.xingheyuzhuan.kgit.model

import okio.Buffer

/**
 * Git 底层对象类型枚举
 */
enum class ObjectType(val typeStr: String, val value: Int) {
    COMMIT("commit", 1),
    TREE("tree", 2),
    BLOB("blob", 3),
    TAG("tag", 4),
    OFS_DELTA("ofs_delta", 6),
    REF_DELTA("ref_delta", 7);

    companion object {
        fun fromValue(value: Int): ObjectType? = entries.find { it.value == value }
    }
}

/**
 * Git 统一对象抽象
 */
sealed interface GitObject {
    val sha1: String
    val rawData: ByteArray
    val type: ObjectType
}

data class CommitObject(
    override val sha1: String,
    override val rawData: ByteArray,
    val treeHash: String,
    val parentHashes: List<String>,
    val author: String,
    val message: String
) : GitObject {
    override val type: ObjectType = ObjectType.COMMIT
}

data class TreeObject(
    override val sha1: String,
    override val rawData: ByteArray,
    val entries: List<TreeEntry>
) : GitObject {
    override val type: ObjectType = ObjectType.TREE
}

data class BlobObject(
    override val sha1: String,
    override val rawData: ByteArray
) : GitObject {
    override val type: ObjectType = ObjectType.BLOB
}

/**
 * Tree 对象内部关联的文件/目录节点信息
 */
data class TreeEntry(
    val mode: String,
    val name: String,
    val sha1: String
)

/**
 * 标准 Git SHA-1 Hash 计算 (header + \0 + payload)
 */
fun calculateGitSha1(typeStr: String, rawData: ByteArray): String {
    val header = "$typeStr ${rawData.size}\u0000".encodeToByteArray()
    val buffer = Buffer()
    buffer.write(header)
    buffer.write(rawData)
    return buffer.sha1().hex()
}