import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    id("maven-publish")
}

group = "com.xingheyuzhuan.kgit"
version = "1.0.0"

kotlin {
    applyDefaultHierarchyTemplate()

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    android {
        namespace = "com.xingheyuzhuan.kgit"
        compileSdk = 37
        minSdk = 24

        withJava()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    // Apple 平台 (iOS, macOS)
    iosX64()
    iosArm64()
    macosX64()
    macosArm64()

    // Desktop Native 平台 (Linux, Windows)
    linuxX64()
    linuxArm64()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.okio)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
        }

        // JVM 平台引擎配置
        getByName("jvmMain") {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }

        // Android 平台引擎配置
        getByName("androidMain") {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }

        // Apple 平台共享引擎 (iOS & macOS)
        val appleMain = create("appleMain") {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        getByName("iosX64Main").dependsOn(appleMain)
        getByName("iosArm64Main").dependsOn(appleMain)
        getByName("macosX64Main").dependsOn(appleMain)
        getByName("macosArm64Main").dependsOn(appleMain)

        // Linux 与 Windows 桌面原生平台引擎
        val desktopNativeMain = create("desktopNativeMain") {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.ktor.client.curl)
            }
        }
        getByName("linuxX64Main").dependsOn(desktopNativeMain)
        getByName("linuxArm64Main").dependsOn(desktopNativeMain)
        getByName("mingwX64Main").dependsOn(desktopNativeMain)

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        artifactId = "kgit"

        pom {
            name.set("kgit")
            description.set("A Kotlin Multiplatform Git library.")
            url.set("https://github.com/XingHeYuZhuan/kgit")

            licenses {
                license {
                    name.set("The Apache Software License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }

            developers {
                developer {
                    id.set("XingHeYuZhuan")
                    name.set("XingHeYuZhuan")
                }
            }

            scm {
                connection.set("scm:git:git://github.com/XingHeYuZhuan/kgit.git")
                developerConnection.set("scm:git:ssh://github.com/XingHeYuZhuan/kgit.git")
                url.set("https://github.com/XingHeYuZhuan/kgit")
            }
        }
    }
}