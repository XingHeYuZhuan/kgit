package com.xingheyuzhuan.kgit.git

import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * 代表一个本地 Git 仓库实例
 *
 * @property repoDir 仓库工作区根目录路径
 * @property fileSystem 文件系统抽象（默认使用物理磁盘，测试时可传入 FakeFileSystem）
 */
class GitRepository(
    val repoDir: Path,
    val fileSystem: FileSystem = FileSystem.SYSTEM
) {
    /**
     * .git 元数据目录路径
     */
    val gitDir: Path = repoDir / ".git"

    init {
        require(isValid()) {
            "The specified path is not a valid Git repository (missing .git directory): $repoDir"
        }
    }

    /**
     * 校验当前路径下是否存在 .git 目录
     */
    fun isValid(): Boolean {
        return fileSystem.exists(gitDir) && fileSystem.metadata(gitDir).isDirectory
    }

    /**
     * 读取当前 HEAD 指针的指向（如 refs/heads/main 或分离 HEAD 状态下的 Commit Hash）
     */
    fun getHead(): String? {
        val headFile = gitDir / "HEAD"
        if (!fileSystem.exists(headFile)) return null

        val content = fileSystem.read(headFile) { readUtf8() }.trim()
        return if (content.startsWith("ref: ")) {
            content.removePrefix("ref: ")
        } else {
            content
        }
    }
}