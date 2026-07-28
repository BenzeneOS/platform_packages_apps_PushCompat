package com.benzeneos.pushcompat

import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.RemoteException
import android.os.UserHandle
import android.util.Slog
import java.io.IOException
import java.util.concurrent.Executors

class UnifiedPushDistributor : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        val connectorToken = intent.getStringExtra(EXTRA_TOKEN) ?: return
        if (connectorToken.isEmpty() ||
            connectorToken.toByteArray(Charsets.UTF_8).size > MAX_TOKEN_BYTES
        ) {
            return
        }
        val userId = senderUserId().takeIf { it >= 0 } ?: return
        val senderPackage =
            resolveSenderPackage(context, userId, intent, connectorToken) ?: return
        val vapid = intent.getStringExtra(EXTRA_VAPID)
        val pendingResult = goAsync()
        executor.execute {
            try {
                when (action) {
                    ACTION_REGISTER -> {
                        register(context, userId, senderPackage, connectorToken, vapid)
                    }

                    ACTION_UNREGISTER -> {
                        unregister(context, userId, senderPackage, connectorToken)
                    }

                    ACTION_MESSAGE_ACK -> {}
                }
            } catch (e: Exception) {
                Slog.e(TAG, "UnifiedPush distributor dispatch failed for $senderPackage", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    // The receiver is singleUser, so the broadcast's own user is always the owner.
    private fun senderUserId(): Int {
        val uid = getSentFromUid()
        return if (uid == Process.INVALID_UID) {
            sendingUser.identifier
        } else {
            UserHandle.getUserId(uid)
        }
    }

    private fun register(
        context: Context,
        userId: Int,
        appId: String,
        connectorToken: String,
        vapid: String?,
    ) {
        val store = RelayStore(context, userId)
        try {
            val bridgeClient = BridgeClient(store)
            val endpoint = bridgeClient.registerUnifiedPush(appId, connectorToken, vapid)
            check(bridgeClient.isCurrent()) { "Bridge endpoint changed during registration" }
            store.saveNativeRegistration(
                NativeRegistration(
                    appId = appId,
                    connectorToken = connectorToken,
                    endpoint = endpoint,
                    vapid = vapid,
                ),
            )
            sendConnectorIntent(
                context,
                userId,
                appId,
                Intent(ACTION_NEW_ENDPOINT)
                    .putExtra(EXTRA_TOKEN, connectorToken)
                    .putExtra(EXTRA_ENDPOINT, endpoint),
            )
            PushService.reconcile(context)
        } catch (e: Exception) {
            val reason = if (e is IOException) "NETWORK" else "INTERNAL_ERROR"
            Slog.e(TAG, "UnifiedPush registration failed for $appId", e)
            try {
                sendConnectorIntent(
                    context,
                    userId,
                    appId,
                    Intent(ACTION_REGISTRATION_FAILED)
                        .putExtra(EXTRA_TOKEN, connectorToken)
                        .putExtra(EXTRA_REASON, reason),
                )
            } catch (reportError: Exception) {
                Slog.e(
                    TAG,
                    "Failed to report UnifiedPush registration failure for $appId",
                    reportError,
                )
            }
        }
    }

    // The app's intent is recorded locally before anything can fail. A
    // bridge call here could be lost to the network and then silently undone
    // by the next reconcile, which re-affirms whatever the store still lists.
    // The reconcile path removes anything the device stopped listing, so it
    // drives the bridge-side removal instead.
    private fun unregister(
        context: Context,
        userId: Int,
        appId: String,
        connectorToken: String,
    ) {
        val store = RelayStore(context, userId)
        if (!store.removeNativeRegistration(appId, connectorToken)) {
            Slog.w(TAG, "Ignoring UnifiedPush unregister for unknown token owned by $appId")
            return
        }
        try {
            sendConnectorIntent(
                context,
                userId,
                appId,
                Intent(ACTION_UNREGISTERED).putExtra(EXTRA_TOKEN, connectorToken),
            )
        } catch (e: Exception) {
            Slog.e(TAG, "Failed to report UnifiedPush unregistration for $appId", e)
        }
        PushService.reconcile(context)
    }

    private fun resolveSenderPackage(
        context: Context,
        userId: Int,
        intent: Intent,
        connectorToken: String,
    ): String? {
        getSentFromPackage()?.let { return it }
        val pendingIntent =
            intent.getParcelableExtra(EXTRA_PENDING_INTENT, PendingIntent::class.java)
        pendingIntent?.takeIf { it.isImmutable }?.creatorPackage?.let { packageName ->
            val targetSdk =
                runCatching {
                    context
                        .asUser(userId)
                        .packageManager
                        .getApplicationInfo(packageName, 0)
                        .targetSdkVersion
                }.getOrNull()
            if (targetSdk != null && targetSdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return packageName
            }
        }
        return RelayStore(context, userId)
            .nativeRegistrations()
            .singleOrNull { it.connectorToken == connectorToken }
            ?.appId
    }

    companion object {
        private const val TAG = "PushCompat"
        private const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
        private const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
        private const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
        private const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
        private const val ACTION_REGISTRATION_FAILED =
            "org.unifiedpush.android.connector.REGISTRATION_FAILED"
        private const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
        private const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_ENDPOINT = "endpoint"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_VAPID = "vapid"
        private const val EXTRA_PENDING_INTENT = "pi"
        private const val EXTRA_BYTES_MESSAGE = "bytesMessage"
        private const val MAX_TOKEN_BYTES = 100
        private val executor = Executors.newFixedThreadPool(4)

        fun deliverMessage(
            context: Context,
            userId: Int,
            appId: String,
            connectorToken: String,
            payload: ByteArray,
        ): Boolean {
            if (RelayStore(context, userId)
                    .findNativeRegistration(appId, connectorToken) == null
            ) {
                Slog.e(TAG, "Dropping UnifiedPush message for unknown token owned by $appId")
                return false
            }
            return try {
                sendConnectorIntent(
                    context,
                    userId,
                    appId,
                    Intent(ACTION_MESSAGE)
                        .putExtra(EXTRA_TOKEN, connectorToken)
                        .putExtra(EXTRA_BYTES_MESSAGE, payload),
                )
                true
            } catch (e: RuntimeException) {
                Slog.e(TAG, "Dropping UnifiedPush message for $appId: broadcast failed", e)
                false
            }
        }

        fun notifyNewEndpoint(
            context: Context,
            userId: Int,
            registration: NativeRegistration,
        ) {
            sendConnectorIntent(
                context,
                userId,
                registration.appId,
                Intent(ACTION_NEW_ENDPOINT)
                    .putExtra(EXTRA_TOKEN, registration.connectorToken)
                    .putExtra(EXTRA_ENDPOINT, registration.endpoint),
            )
        }

        private fun sendConnectorIntent(
            context: Context,
            userId: Int,
            appId: String,
            intent: Intent,
        ) {
            val action = checkNotNull(intent.action)
            val delivered =
                try {
                    ActivityManager.getService().deliverUnifiedPushConnectorIntent(
                        userId,
                        appId,
                        action,
                        intent.extras ?: Bundle.EMPTY,
                    )
                } catch (e: RemoteException) {
                    throw RuntimeException("UnifiedPush connector broker failed", e)
                }
            check(delivered) { "UnifiedPush connector broker rejected $action for $appId" }
        }
    }
}

class UnifiedPushLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        val data = intent.data
        if (caller == null || data?.scheme != "unifiedpush" || data.host != "link") {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val identityIntent = Intent().setPackage("org.unifiedpush.dummy_app")
        val pendingIntent =
            PendingIntent.getBroadcast(
                this,
                0,
                identityIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        setResult(
            RESULT_OK,
            Intent().putExtra("pi", pendingIntent),
        )
        finish()
    }
}
