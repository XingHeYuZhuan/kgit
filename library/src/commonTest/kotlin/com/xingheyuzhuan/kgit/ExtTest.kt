package com.xingheyuzhuan.kgit

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtTest {

    @Test
    fun testDownloadRepositoryWithoutDotGit() = runTest {
        val repoUrl = "https://github.com/octocat/Hello-World.git"
        val fakeFileSystem = FakeFileSystem()
        val targetDir = "/test-workspace/hello-world".toPath()

        // 执行纯源码下载命令
        val resultDir = Ext.downloadRepository()
            .setUri(repoUrl)
            .setDirectory(targetDir)
            .call(fakeFileSystem)

        // 验证目标工作区目录已被成功创建并写入文件
        assertTrue(fakeFileSystem.exists(resultDir), "下载的目标目录应该存在")

        // 核心断言：扩展下载不应生成 .git 目录
        val dotGitDir = resultDir / ".git"
        assertFalse(fakeFileSystem.exists(dotGitDir), "非原生下载不应包含 .git 元数据目录")
    }
}