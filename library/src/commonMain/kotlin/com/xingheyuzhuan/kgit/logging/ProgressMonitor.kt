package com.xingheyuzhuan.kgit.logging

/**
 * 进度监听器接口
 */
interface ProgressMonitor {
    /**
     * 开始一个新任务阶段
     * @param title 任务描述
     * @param totalWork 总工作量（若未知可传入 UNKNOWN）
     */
    fun beginTask(title: String, totalWork: Int) {}

    /**
     * 更新当前任务的已完成工作量
     */
    fun update(completedWork: Int) {}

    /**
     * 当前任务阶段结束
     */
    fun endTask() {}

    companion object {
        const val UNKNOWN = -1

        /**
         * 默认的空实现（无操作），用于未显式设置监听器时的兜底
         */
        val DEFAULT: ProgressMonitor = object : ProgressMonitor {}
    }
}