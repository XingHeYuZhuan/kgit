package com.xingheyuzhuan.kgit.checkout

import com.xingheyuzhuan.kgit.model.BlobObject
import com.xingheyuzhuan.kgit.model.TreeObject
import com.xingheyuzhuan.kgit.storage.ObjectStore
import okio.FileSystem
import okio.Path

object TreeCheckout {

    /**
     * 根据根 Tree 的 SHA-1，递归将整个树结构落盘到指定目录
     *
     * @param store 内存对象池
     * @param treeSha1 根 Tree 对象的 SHA-1 Hash
     * @param targetDir 本地目标输出路径
     * @param fileSystem 显式注入文件系统实例（如 JVM/Android 端传入 FileSystem.SYSTEM，测试时传入 FakeFileSystem）
     * @param filter 路径过滤器，返回 false 则跳过该文件的写入
     */
    fun checkoutTree(
        store: ObjectStore,
        treeSha1: String,
        targetDir: Path,
        fileSystem: FileSystem,
        filter: ((relativePath: String) -> Boolean)? = null
    ) {
        val rootTree = store.getAs<TreeObject>(treeSha1)
            ?: error("Root tree object not found in object store: $treeSha1")

        fileSystem.createDirectories(targetDir)
        walk(
            store = store,
            currentTree = rootTree,
            baseDir = targetDir,
            currentPath = "",
            fileSystem = fileSystem,
            filter = filter
        )
    }

    private fun walk(
        store: ObjectStore,
        currentTree: TreeObject,
        baseDir: Path,
        currentPath: String,
        fileSystem: FileSystem,
        filter: ((String) -> Boolean)?
    ) {
        for (entry in currentTree.entries) {
            val relativePath = if (currentPath.isEmpty()) entry.name else "$currentPath/${entry.name}"
            val isDirectory = entry.mode == "40000" || entry.mode == "040000"

            if (isDirectory) {
                val subTree = store.getAs<TreeObject>(entry.sha1)
                    ?: error("Sub-tree object not found: ${entry.sha1} (path: $relativePath)")
                walk(store, subTree, baseDir, relativePath, fileSystem, filter)
            } else {
                if (filter != null && !filter(relativePath)) {
                    continue
                }

                val blob = store.getAs<BlobObject>(entry.sha1)
                    ?: error("Blob object not found: ${entry.sha1} (path: $relativePath)")

                val filePath = baseDir / relativePath

                filePath.parent?.let { parentPath ->
                    fileSystem.createDirectories(parentPath)
                }

                fileSystem.write(filePath) {
                    write(blob.rawData)
                }
            }
        }
    }
}