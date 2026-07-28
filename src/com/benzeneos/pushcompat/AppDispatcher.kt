package com.benzeneos.pushcompat

import android.app.ActivityManager
import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.PowerExemptionManager
import android.os.RemoteException
import android.os.UserHandle
import android.util.Slog
import org.json.JSONObject

object AppDispatcher {
    private const val TAG = "PushCompat"
    private const val ACTION_C2DM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE"

    fun deliverToken(
        userId: Int,
        packageName: String,
        token: String,
    ): Boolean =
        try {
            ActivityManager.getService().deliverPushCompatToken(userId, packageName, token)
        } catch (e: RemoteException) {
            Slog.e(TAG, "Token broker failed for $packageName", e)
            false
        } catch (e: RuntimeException) {
            Slog.e(TAG, "Token broker rejected $packageName", e)
            false
        }

    fun deliverMessage(
        context: Context,
        userId: Int,
        packageName: String,
        payload: ByteArray,
    ): Boolean {
        val receiver = FirebaseDiscovery.find(context, packageName, userId)?.receiverName
        if (receiver == null) {
            Slog.e(TAG, "Dropping message for $packageName: no C2DM receiver")
            return false
        }
        val fields =
            try {
                JSONObject(payload.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                Slog.e(TAG, "Dropping message for $packageName: payload is not a JSON object", e)
                return false
            }
        if (fields.optString("google.message_id").isEmpty()) {
            Slog.e(TAG, "Dropping message for $packageName: google.message_id is missing")
            return false
        }
        if (!fields.has("google.c.sender.id")) {
            fields.optString("from").takeIf { it.isNotEmpty() }?.let {
                fields.put("google.c.sender.id", it)
            }
        }

        return try {
            val intent = Intent(ACTION_C2DM_RECEIVE).setClassName(packageName, receiver)
            val keys = fields.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = fields.opt(key)
                if (value != null && value !== JSONObject.NULL) {
                    intent.putExtra(
                        key,
                        if (value is String) value else value.toString(),
                    )
                }
            }
            sendPushBroadcast(context, userId, intent)
            true
        } catch (e: RuntimeException) {
            Slog.e(TAG, "Dropping message for $packageName: broadcast failed", e)
            false
        }
    }

    fun sendPushBroadcast(
        context: Context,
        userId: Int,
        intent: Intent,
    ) {
        val options = BroadcastOptions.makeBasic()
        options.setTemporaryAppAllowlist(
            10_000L,
            PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
            PowerExemptionManager.REASON_PUSH_MESSAGING,
            "PushCompat delivery",
        )
        context.sendBroadcastAsUser(intent, UserHandle.of(userId), null, options.toBundle())
    }
}
