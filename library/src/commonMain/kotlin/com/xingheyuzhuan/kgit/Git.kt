package com.xingheyuzhuan.kgit

import com.xingheyuzhuan.kgit.git.*
import okio.FileSystem
import okio.Path
import okio.SYSTEM

object Git {
    /**
     * 对应 git clone 命令
     */
    fun clone(): CloneCommand = CloneCommand()

    /**
     * 对应 git ls-remote 命令
     */
    fun lsRemote(): LsRemoteCommand = LsRemoteCommand()

    /**
     * 打开本地已有仓库实例
     * @param fileSystem 文件系统抽象，默认使用磁盘，测试时可传入 FakeFileSystem
     */
    fun open(
        directory: Path,
        fileSystem: FileSystem = FileSystem.SYSTEM
    ): GitRepository = GitRepository(directory, fileSystem)
}