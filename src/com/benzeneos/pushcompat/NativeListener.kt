package com.benzeneos.pushcompat

import java.util.function.Consumer

object NativeListener {
    init {
        System.loadLibrary("pushcompat_jni")
    }

    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeCheckIn(handle: Long): String

    external fun nativeRegister(
        handle: Long,
        sessionJson: String,
        credentialsJson: String,
    ): String

    external fun nativeRun(
        handle: Long,
        sessionsJson: String,
        registrationsJson: String,
        callback: Consumer<String>,
    )

    external fun nativeStop(handle: Long)

    fun checkIn(): String {
        val handle = nativeCreate()
        return try {
            nativeCheckIn(handle)
        } finally {
            nativeDestroy(handle)
        }
    }

    fun register(
        sessionJson: String,
        credentialsJson: String,
    ): String {
        val handle = nativeCreate()
        return try {
            nativeRegister(handle, sessionJson, credentialsJson)
        } finally {
            nativeDestroy(handle)
        }
    }
}
