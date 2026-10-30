package io.github.powerycy.rideassist

import android.content.Context

/**
 * 从任意 Context 拿到 [YybmsApp] 实例，供 ViewModel Factory 使用。
 * Application 在 onCreate 时注册，进程内单例。
 */
object YybmsAppHolder {

    @Volatile
    private var instance: YybmsApp? = null

    fun register(app: YybmsApp) {
        instance = app
    }

    fun from(context: Context): YybmsApp =
        instance ?: (context.applicationContext as YybmsApp).also { instance = it }
}