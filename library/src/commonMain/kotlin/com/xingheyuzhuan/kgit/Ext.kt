package com.xingheyuzhuan.kgit

import com.xingheyuzhuan.kgit.ext.RepoDownloadCommand

object Ext {
    fun downloadRepository(): RepoDownloadCommand = RepoDownloadCommand()
}