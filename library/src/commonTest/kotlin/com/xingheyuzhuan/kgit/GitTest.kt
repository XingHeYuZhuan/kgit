package com.xingheyuzhuan.kgit

import com.xingheyuzhuan.kgit.logging.ProgressMonitor
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitTest {

    @Test
    fun testLsRemote() = runTest {
        val repoUrl = "https://github.com/octocat/Hello-World.git"

        val discoveryResult = Git.lsRemote()
            .setUri(repoUrl)
            .call()

        assertTrue(discoveryResult.isNotEmpty(), "远程仓库应包含有效的引用列表")
    }

    @Test
    fun testClone() = runTest {
        val repoUrl = "https://github.com/octocat/Hello-World.git"
        val fakeFileSystem = FakeFileSystem()
        val targetDir = "/test-clone/hello-world".toPath()

        // 执行克隆（依赖 FakeFileSystem 隔离物理磁盘）
        val repo = Git.clone()
            .setUri(repoUrl)
            .setDirectory(targetDir)
            .call(fakeFileSystem)

        assertTrue(fakeFileSystem.exists(targetDir / ".git"), "克隆成功后必须生成 .git 元数据目录")
        assertTrue(repo.isValid(), "克隆生成的仓库实例应为有效状态")
    }

    @Test
    fun testCloneProgressMonitor() = runTest {
        val repoUrl = "https://github.com/octocat/Hello-World.git"
        val fakeFileSystem = FakeFileSystem()
        val targetDir = "/test-clone/hello-world-progress".toPath()

        val taskTitles = mutableListOf<String>()
        val customMonitor = object : ProgressMonitor {
            override fun beginTask(title: String, totalWork: Int) {
                taskTitles.add(title)
            }
        }

        // 注入自定义进度监听器验证执行阶段
        Git.clone()
            .setUri(repoUrl)
            .setDirectory(targetDir)
            .setProgressMonitor(customMonitor)
            .call(fakeFileSystem)

        assertTrue(taskTitles.contains("Discovering remote refs"), "应包含服务发现阶段")
        assertTrue(taskTitles.contains("Downloading packfile"), "应包含下载 Packfile 阶段")
        assertTrue(taskTitles.contains("Checking out working tree"), "应包含检出工作区阶段")
    }

    @Test
    fun testOpen() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val targetDir = "/test-open/hello-world".toPath()

        // 模拟本地已存在的有效仓库结构（创建 .git 目录）
        fakeFileSystem.createDirectories(targetDir / ".git")

        // 测试打开本地已有仓库
        val repo = Git.open(targetDir, fileSystem = fakeFileSystem)

        assertNotNull(repo, "应成功返回 GitRepository 实例")
        assertTrue(repo.isValid(), "打开的仓库实例校验应通过")
    }
}