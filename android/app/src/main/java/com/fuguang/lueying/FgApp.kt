package com.fuguang.lueying

import android.app.Application

/**
 * 进程一起来最先跑的地方（在任何页面、服务、开机广播之前）。
 * 只做一件事：有确认过要恢复的备份就先换进去（Backup.applyStaged）。设置文件必须在谁都还没读之前换，
 * 否则读进内存的旧设置之后会把换进去的写回去。别的初始化仍然在 MainActivity。
 */
class FgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        restored = Backup.applyStaged(this)
    }

    companion object {
        /** 这次启动是不是刚恢复完一份备份（界面上可以说一声）。 */
        @Volatile var restored = false
    }
}
