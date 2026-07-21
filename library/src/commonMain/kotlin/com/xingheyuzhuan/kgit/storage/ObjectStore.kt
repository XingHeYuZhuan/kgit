package com.xingheyuzhuan.kgit.storage

import com.xingheyuzhuan.kgit.model.GitObject

/**
 * 内存 Git 对象索引池
 */
class ObjectStore(objects: List<GitObject>) {
    private val hashMap: Map<String, GitObject> = objects.associateBy { it.sha1 }

    /**
     * 根据 SHA-1 Hash 查找对象
     */
    fun get(sha1: String): GitObject? = hashMap[sha1]

    /**
     * 强类型获取 Git 对象
     */
    inline fun <reified T : GitObject> getAs(sha1: String): T? {
        return get(sha1) as? T
    }
}