# kgit

`kgit` 是一个基于 Kotlin Multiplatform (KMP) 开发的跨平台 Git 基础库，仓库地址为 [https://github.com/XingHeYuZhuan/kgit](https://github.com/XingHeYuZhuan/kgit)。

---

## 项目集成（通过 Maven Central 引入）

通过 Maven Central 仓库，可以直接将该库作为依赖项引入。

1. 在项目根目录的 `settings.gradle.kts` 中确保包含 `mavenCentral()` 仓库源：

```kotlin
repositories {
    google()
    mavenCentral()
}

```

2. 在目标模块的 `build.gradle.kts` 中添加依赖：

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.xingheyuzhuan:kgit:1.0.0")
        }
    }
}

```

---

## 完整接口与 API 文档

### 1. `Git` 入口对象

提供核心 Git 操作命令的构建入口。

* **`Git.clone()`**
* 返回 **`CloneCommand`**，用于执行完整的克隆操作（包含初始化 `.git` 元数据目录以及检出工作区文件）。
* 可配置方法：
* `setUri(uri: String)`：设置远程仓库地址。
* `setDirectory(dir: Path)`：设置目标落盘路径。
* `setBranch(branch: String)`：指定目标分支。
* `setToken(token: String)`：设置认证 Token（适用于私有仓库）。
* `setHttpClient(client: GitHttpClient)`：自定义 HTTP 客户端。
* `setProgressMonitor(monitor: ProgressMonitor)`：设置进度监听器。
* `suspend fun call(fileSystem: FileSystem): GitRepository`：执行克隆并返回 `GitRepository` 实例。




* **`Git.lsRemote()`**
* 返回 **`LsRemoteCommand`**，用于查询远程仓库的引用（Refs）列表。
* 可配置方法：
* `setUri(uri: String)`：设置远程仓库地址。
* `setToken(token: String)`：设置认证 Token。
* `setHeadsOnly(headsOnly: Boolean)`：仅过滤分支引用。
* `setTagsOnly(tagsOnly: Boolean)`：仅过滤标签引用。
* `addPattern(pattern: String)`：添加匹配模式串。
* `setHttpClient(client: GitHttpClient)`：自定义 HTTP 客户端。
* `suspend fun call(): List<RemoteRef>`：执行查询并返回匹配的远程引用列表。




* **`Git.open()`**
* 返回 **`GitRepository`** 实例，用于操作本地已存在的 Git 仓库。
* 参数：`directory: Path`, `fileSystem: FileSystem`（默认 `FileSystem.SYSTEM`）。
* 核心属性与方法：
* `val repoDir: Path`：仓库工作区根目录路径。
* `val gitDir: Path`：`.git` 元数据目录路径。
* `fun isValid(): Boolean`：校验当前路径下是否存在有效的 `.git` 目录。
* `fun getHead(): String?`：读取当前 HEAD 指针的指向（如分支名引用或分离状态下的 Commit Hash）。





### 2. `Ext` 扩展入口对象

提供扩展功能的构建入口。

* **`Ext.downloadRepository()`**
* 返回 **`RepoDownloadCommand`**，用于仅下载并检出目标分支的工作区文件（完全跳过 `.git` 目录与元数据的生成）。
* 可配置方法：
* `setUri(uri: String)`：设置远程仓库地址。
* `setDirectory(dir: Path)`：设置目标落盘路径。
* `setBranch(branch: String)`：指定目标分支。
* `setToken(token: String)`：设置认证 Token。
* `setHttpClient(client: GitHttpClient)`：自定义 HTTP 客户端。
* `setProgressMonitor(monitor: ProgressMonitor)`：设置进度监听器。
* `suspend fun call(fileSystem: FileSystem): Path`：执行下载并返回目标目录路径。





### 3. `ProgressMonitor` 进度监听器

任务进度监控接口。

* 常量：`const val UNKNOWN = -1`。
* 默认实现：`val DEFAULT: ProgressMonitor`（空实现）。
* 核心方法：
* `fun beginTask(title: String, totalWork: Int)`：开始一个新任务阶段。
* `fun update(completedWork: Int)`：更新当前任务的已完成工作量。
* `fun endTask()`：当前任务阶段结束。



---

## 调用示例

### 1. 克隆远程仓库 (`Git.clone`)

```kotlin
import com.xingheyuzhuan.kgit.Git
import okio.Path.Companion.toPath

val repository = Git.clone()
    .setUri("https://github.com/XingHeYuZhuan/kgit.git")
    .setDirectory("path/to/target".toPath())
    .setBranch("main")
    .setToken("your-auth-token") // 可选（针对私有仓库）
    .call(okio.FileSystem.SYSTEM)

```

### 2. 查询远程引用 (`Git.lsRemote`)

```kotlin
import com.xingheyuzhuan.kgit.Git

val refs = Git.lsRemote()
    .setUri("https://github.com/XingHeYuZhuan/kgit.git")
    .setHeadsOnly(true)
    .call()

refs.forEach { ref ->
    println("${ref.refName} -> ${ref.commitHash}")
}

```

### 3. 仅下载工作区源码 (`Ext.downloadRepository`)

```kotlin
import com.xingheyuzhuan.kgit.Ext
import okio.Path.Companion.toPath

val targetDir = Ext.downloadRepository()
    .setUri("https://github.com/XingHeYuZhuan/kgit.git")
    .setDirectory("path/to/target".toPath())
    .call()

```

### 4. 操作本地已有仓库 (`Git.open`)

```kotlin
import com.xingheyuzhuan.kgit.Git
import okio.Path.Companion.toPath

val repo = Git.open("path/to/local/repo".toPath())
val currentHead = repo.getHead()
println("Current HEAD: $currentHead")
```