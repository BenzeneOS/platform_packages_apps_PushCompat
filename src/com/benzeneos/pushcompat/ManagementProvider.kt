package com.benzeneos.pushcompat

import android.annotation.IntDef
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.util.Slog
import org.json.JSONObject

object PushCompatContract {
    const val AUTHORITY = "com.benzeneos.pushcompat.management"

    val STATUS_URI: Uri = Uri.parse("content://$AUTHORITY/status")
    val APPS_URI: Uri = Uri.parse("content://$AUTHORITY/apps")
    val UNIFIED_PUSH_URI: Uri = Uri.parse("content://$AUTHORITY/unified_push")
    val DELIVERIES_URI: Uri = Uri.parse("content://$AUTHORITY/deliveries")

    const val METHOD_GET_STATUS = "getStatus"
    const val METHOD_SET_APP_ENABLED = "setAppEnabled"
    const val METHOD_SET_DELIVERY_MODE = "setDeliveryMode"
    const val METHOD_SET_BRIDGE_URL = "setBridgeUrl"
    const val METHOD_RECONCILE_NOW = "reconcileNow"

    const val KEY_ERROR = "error"
    const val KEY_MESSAGE = "message"
    const val KEY_ENABLED = "enabled"
    const val KEY_USER_ID = "userId"
    const val KEY_MODE = "mode"
    const val KEY_DELIVERY_MODE = "deliveryMode"
    const val KEY_GMS_PRESENT = "gmsPresent"
    const val KEY_SOCKET_STATE = "socketState"
    const val KEY_SOCKET_STATE_SINCE = "socketStateSince"
    const val KEY_CONNECTED_SINCE = "connectedSince"
    const val KEY_CONNECT_FAILURE_COUNT = "connectFailureCount"
    const val KEY_LAST_SOCKET_ERROR = "lastSocketError"
    const val KEY_BRIDGE_URL = "bridgeUrl"
    const val KEY_ENABLED_COUNT = "enabledCount"
    const val KEY_ACTIVE_COUNT = "activeCount"
    const val KEY_UNAVAILABLE_COUNT = "unavailableCount"
    const val KEY_UNIFIED_PUSH_COUNT = "unifiedPushCount"

    const val COLUMN_USER_ID = "userId"
    const val COLUMN_PACKAGE_NAME = "packageName"
    const val COLUMN_LABEL = "label"
    const val COLUMN_ENABLED = "enabled"
    const val COLUMN_ACTIVE = "active"
    const val COLUMN_AVAILABLE = "available"
    const val COLUMN_TIMESTAMP = "timestamp"
    const val COLUMN_APP_ID = "appId"
    const val COLUMN_KIND = "kind"
    const val COLUMN_DELIVERED = "delivered"
    const val COLUMN_DETAIL = "detail"

    const val ERROR_NONE = 0
    const val ERROR_INVALID_URL = 1
    const val ERROR_UNKNOWN_PACKAGE = 2
    const val ERROR_NOT_READY = 3
    const val ERROR_INTERNAL = 4
    const val ERROR_INVALID_MODE = 5
    const val ERROR_UNKNOWN_USER = 6

    const val MODE_PUSHCOMPAT = 1
    const val MODE_GMS = 3
    const val DELIVERY_MODE_BRIDGE = 0
    const val DELIVERY_MODE_ON_DEVICE = 1
    const val SOCKET_IDLE = 0
    const val SOCKET_CONNECTING = 1
    const val SOCKET_CONNECTED = 2
}

@IntDef(PushCompatContract.MODE_PUSHCOMPAT, PushCompatContract.MODE_GMS)
@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.TYPE,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.LOCAL_VARIABLE,
)
annotation class PushCompatMode

@IntDef(PushCompatContract.DELIVERY_MODE_BRIDGE, PushCompatContract.DELIVERY_MODE_ON_DEVICE)
@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.TYPE,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.LOCAL_VARIABLE,
)
annotation class DeliveryMode

@IntDef(
    PushCompatContract.SOCKET_IDLE,
    PushCompatContract.SOCKET_CONNECTING,
    PushCompatContract.SOCKET_CONNECTED,
)
@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.TYPE,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.LOCAL_VARIABLE,
)
annotation class SocketState

class ManagementProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        if (method == METHOD_DEBUG_CHECK_IN || method == METHOD_DEBUG_RUN_MCS) {
            enforceRootCaller()
        } else {
            enforceCaller()
        }
        val store =
            if (method in USER_SCOPED_METHODS) {
                storeFor(extras)
                    ?: return errorResult(
                        PushCompatContract.ERROR_UNKNOWN_USER,
                        "PushCompat does not serve this user",
                    )
            } else {
                RelayStore(providerContext(), UserHandle.myUserId())
            }
        return try {
            when (method) {
                PushCompatContract.METHOD_GET_STATUS -> status()
                PushCompatContract.METHOD_SET_APP_ENABLED ->
                    setAppEnabled(
                        store,
                        arg,
                        extras?.getBoolean(PushCompatContract.KEY_ENABLED, false),
                    )
                PushCompatContract.METHOD_SET_DELIVERY_MODE ->
                    setDeliveryMode(
                        extras
                            ?.takeIf { it.containsKey(PushCompatContract.KEY_DELIVERY_MODE) }
                            ?.getInt(PushCompatContract.KEY_DELIVERY_MODE),
                    )
                PushCompatContract.METHOD_SET_BRIDGE_URL -> setBridgeUrl(store, arg)
                PushCompatContract.METHOD_RECONCILE_NOW ->
                    reconcileNow(
                        extras
                            ?.takeIf { it.containsKey(PushCompatContract.KEY_USER_ID) }
                            ?.getInt(PushCompatContract.KEY_USER_ID),
                    )
                METHOD_DEBUG_CHECK_IN -> debugCheckIn()
                METHOD_DEBUG_RUN_MCS -> debugRunMcs(store, arg)
                else -> errorResult(
                    PushCompatContract.ERROR_INTERNAL,
                    "Unknown method: $method",
                )
            }
        } catch (e: Exception) {
            errorResult(PushCompatContract.ERROR_INTERNAL, e.message)
        }
    }

    private fun storeFor(extras: Bundle?): RelayStore? {
        val context = providerContext()
        val userId =
            extras
                ?.takeIf { it.containsKey(PushCompatContract.KEY_USER_ID) }
                ?.getInt(PushCompatContract.KEY_USER_ID)
                ?: UserHandle.myUserId()
        return RelayStore(context, userId).takeIf { userId in relayUserIds(context) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceCaller()
        return when (uri.path) {
            "/apps" -> apps()
            "/unified_push" -> unifiedPushApps()
            "/deliveries" -> deliveries(uri.getQueryParameter("limit")?.toIntOrNull())
            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }
    }

    override fun getType(uri: Uri): String {
        enforceCaller()
        return when (uri.path) {
            "/apps" -> "vnd.android.cursor.dir/vnd.${PushCompatContract.AUTHORITY}.app"
            "/unified_push" ->
                "vnd.android.cursor.dir/vnd.${PushCompatContract.AUTHORITY}.unified_push_app"
            "/deliveries" ->
                "vnd.android.cursor.dir/vnd.${PushCompatContract.AUTHORITY}.delivery"
            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceCaller()
        throw UnsupportedOperationException("Insert is not supported")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceCaller()
        throw UnsupportedOperationException("Delete is not supported")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        enforceCaller()
        throw UnsupportedOperationException("Update is not supported")
    }

    private fun status(): Bundle {
        val context = providerContext()
        val ownerId = UserHandle.myUserId()
        val store = RelayStore(context, ownerId)
        val gmsPresent = RelayRegistry.isGmsPresent(context, ownerId)
        var enabledCount = 0
        var activeCount = 0
        var unavailableCount = 0
        var unifiedPushCount = 0
        relayUserIds(context).forEach { userId ->
            val userStore = RelayStore(context, userId)
            val selection = RelayRegistry.enabledFirebaseTargets(context, userStore)
            enabledCount += selection.ready.size
            unavailableCount += selection.unavailablePackages.size
            activeCount += userStore.frameworkEnabledPackages().size
            unifiedPushCount += userStore.nativeRegistrations().distinctBy { it.appId }.size
        }
        val socketHealth = PushService.socketHealth()
        return successResult().apply {
            @PushCompatMode
            val mode =
                if (gmsPresent) {
                    PushCompatContract.MODE_GMS
                } else {
                    PushCompatContract.MODE_PUSHCOMPAT
                }
            putInt(PushCompatContract.KEY_MODE, mode)
            putBoolean(PushCompatContract.KEY_GMS_PRESENT, gmsPresent)
            putInt(PushCompatContract.KEY_SOCKET_STATE, socketHealth.state)
            putLong(
                PushCompatContract.KEY_SOCKET_STATE_SINCE,
                socketHealth.stateSinceMillis,
            )
            putLong(PushCompatContract.KEY_CONNECTED_SINCE, socketHealth.connectedSinceMillis)
            putInt(
                PushCompatContract.KEY_CONNECT_FAILURE_COUNT,
                socketHealth.consecutiveConnectFailures,
            )
            putString(PushCompatContract.KEY_LAST_SOCKET_ERROR, socketHealth.lastError)
            putString(PushCompatContract.KEY_BRIDGE_URL, store.bridgeUrl.value)
            putInt(PushCompatContract.KEY_ENABLED_COUNT, enabledCount)
            putInt(PushCompatContract.KEY_ACTIVE_COUNT, activeCount)
            putInt(PushCompatContract.KEY_UNAVAILABLE_COUNT, unavailableCount)
            putInt(PushCompatContract.KEY_UNIFIED_PUSH_COUNT, unifiedPushCount)
            putInt(PushCompatContract.KEY_DELIVERY_MODE, store.deliveryMode)
        }
    }

    private fun setAppEnabled(
        store: RelayStore,
        packageName: String?,
        enabled: Boolean?,
    ): Bundle {
        if (packageName.isNullOrEmpty()) {
            return errorResult(
                PushCompatContract.ERROR_UNKNOWN_PACKAGE,
                "Package name is missing",
            )
        }
        val context = providerContext()
        if (enabled != true) {
            store.setPackageEnabled(packageName, false)
            store.markRegistrationDue()
            notifyApps(context)
            PushService.reconcile(context)
            return successResult()
        }
        val target =
            FirebaseDiscovery.find(context, packageName, store.userId)
                ?: return errorResult(
                    PushCompatContract.ERROR_UNKNOWN_PACKAGE,
                    "Unknown package: $packageName",
                )
        if (!target.isReady) {
            return errorResult(
                PushCompatContract.ERROR_NOT_READY,
                "Firebase setup is incomplete for $packageName",
            )
        }
        store.setPackageEnabled(packageName, true)
        store.markRegistrationDue()
        notifyApps(context)
        PushService.reconcile(context)
        return successResult()
    }

    private fun setDeliveryMode(mode: Int?): Bundle {
        if (
            mode != PushCompatContract.DELIVERY_MODE_BRIDGE &&
                mode != PushCompatContract.DELIVERY_MODE_ON_DEVICE
        ) {
            return errorResult(
                PushCompatContract.ERROR_INVALID_MODE,
                "Unknown delivery mode: $mode",
            )
        }
        val context = providerContext()
        RelayStore(context, UserHandle.myUserId()).deliveryMode = mode
        if (mode == PushCompatContract.DELIVERY_MODE_ON_DEVICE) {
            val debugTransport =
                synchronized(DEBUG_MCS_LOCK) {
                    activeDebugMcsPackage = null
                    activeDebugMcsTransport.also { activeDebugMcsTransport = null }
                }
            debugTransport?.close()
        }
        relayUserIds(context).forEach { userId ->
            RelayStore(context, userId).markRegistrationDue()
        }
        notifyApps(context)
        notifyStatus(context)
        PushService.reconcile(context)
        return successResult()
    }

    private fun setBridgeUrl(
        store: RelayStore,
        value: String?,
    ): Bundle {
        val bridgeUrl =
            BridgeUrl.parse(value.orEmpty())
                ?: return errorResult(
                    PushCompatContract.ERROR_INVALID_URL,
                    "Enter a valid HTTPS URL",
                )
        val context = providerContext()
        store.bridgeUrl = bridgeUrl
        relayUserIds(context).forEach { RelayStore(context, it).markRegistrationDue() }
        notifyStatus(context)
        PushService.reconcile(context)
        return successResult()
    }

    private fun reconcileNow(userId: Int?): Bundle {
        val context = providerContext()
        val users = userId?.let(::listOf) ?: relayUserIds(context)
        users.forEach { RelayStore(context, it).markRegistrationDue() }
        PushService.reconcile(context)
        return successResult()
    }

    private fun debugCheckIn(): Bundle {
        val state = JSONObject(NativeListener.checkIn())
        return successResult().apply {
            putString(KEY_DEBUG_ANDROID_ID, state.getString(KEY_DEBUG_ANDROID_ID))
        }
    }

    private fun debugRunMcs(
        store: RelayStore,
        packageName: String?,
    ): Bundle {
        require(!packageName.isNullOrEmpty()) { "package name is missing" }
        val userId = store.userId
        check(store.deliveryMode != PushCompatContract.DELIVERY_MODE_ON_DEVICE) {
            "on-device delivery is already the global mode"
        }
        check(!OnDeviceMcsTransport.isRunning(userId, packageName)) {
            "on-device MCS is already running for $packageName"
        }
        val context = providerContext()
        val target =
            FirebaseDiscovery.find(context, packageName, userId)
                ?: error("unknown package: $packageName")
        check(target.isReady) { "Firebase setup is incomplete for $packageName" }

        val sessionJson = NativeListener.checkIn()
        val registrationJson =
            NativeListener.register(sessionJson, target.nativeCredentialsJson())
        val registration = JSONObject(registrationJson)
        val token = registration.getString(KEY_DEBUG_FCM_TOKEN)

        val transport =
            OnDeviceMcsTransport(
                listOf(
                    OnDeviceMcsIdentity(
                        userId = userId,
                        appId = packageName,
                        sessionJson = sessionJson,
                        registrationJson = registrationJson,
                    ),
                ),
            )
        try {
            synchronized(DEBUG_MCS_LOCK) {
                check(
                    store.deliveryMode != PushCompatContract.DELIVERY_MODE_ON_DEVICE,
                ) {
                    "on-device delivery is already the global mode"
                }
                check(!OnDeviceMcsTransport.isRunning(userId, packageName)) {
                    "on-device MCS is already running for $packageName"
                }
                activeDebugMcsTransport?.close()
                activeDebugMcsTransport = transport
                activeDebugMcsPackage = packageName
                check(AppDispatcher.deliverToken(userId, packageName, token)) {
                    "failed to deliver FCM token to $packageName"
                }
            }
        } catch (e: Exception) {
            synchronized(DEBUG_MCS_LOCK) {
                if (activeDebugMcsTransport === transport) {
                    activeDebugMcsTransport = null
                    activeDebugMcsPackage = null
                }
            }
            transport.close()
            throw e
        }
        Thread(
            {
                try {
                    transport.run(
                        onSocketState = { state ->
                            Slog.i(TAG, "Debug MCS socket state for $packageName: $state")
                        },
                        onMessage = { messageUserId, message ->
                            val delivered =
                                AppDispatcher.deliverMessage(
                                    context,
                                    messageUserId,
                                    message.appId,
                                    message.payload,
                                )
                            Slog.i(
                                TAG,
                                "Debug MCS message for ${message.appId}; delivered=$delivered",
                            )
                            delivered
                        },
                    )
                } catch (e: Exception) {
                    Slog.e(TAG, "Debug MCS transport failed for $packageName", e)
                } finally {
                    synchronized(DEBUG_MCS_LOCK) {
                        if (activeDebugMcsTransport === transport) {
                            activeDebugMcsTransport = null
                            activeDebugMcsPackage = null
                        }
                    }
                }
            },
            "PushCompat-debug-mcs",
        ).apply {
            isDaemon = true
            start()
        }

        return successResult().apply {
            putString(
                KEY_DEBUG_ANDROID_ID,
                JSONObject(sessionJson).getString(KEY_DEBUG_ANDROID_ID),
            )
            putString(KEY_DEBUG_FCM_TOKEN, token)
        }
    }

    private fun apps(): Cursor {
        val context = providerContext()
        val cursor = MatrixCursor(APP_COLUMNS)
        relayUserIds(context).forEach { userId ->
            val store = RelayStore(context, userId)
            val activePackages = store.frameworkEnabledPackages()
            val selection = RelayRegistry.enabledFirebaseTargets(context, store)
            val listedTargets = linkedMapOf<String, FirebaseTarget?>()
            selection.availableTargets.forEach { target ->
                listedTargets[target.packageName] = target
            }
            selection.unavailableTargets.forEach { (packageName, target) ->
                listedTargets[packageName] = target
            }
            val enabledPackages = store.enabledPackages()
            listedTargets.forEach { (packageName, target) ->
                cursor.addRow(
                    arrayOf<Any?>(
                        userId,
                        packageName,
                        target?.label ?: packageName,
                        if (packageName in enabledPackages) 1 else 0,
                        if (packageName in activePackages) 1 else 0,
                        if (packageName in selection.unavailablePackages) 0 else 1,
                    ),
                )
            }
        }
        return cursor
    }

    private fun unifiedPushApps(): Cursor {
        val context = providerContext()
        val cursor = MatrixCursor(UNIFIED_PUSH_COLUMNS)
        relayUserIds(context).forEach { userId ->
            RelayStore(context, userId)
                .nativeRegistrations()
                .map { it.appId }
                .distinct()
                .sorted()
                .forEach { packageName ->
                    cursor.addRow(arrayOf<Any?>(userId, packageName))
                }
        }
        return cursor
    }

    private fun deliveries(limit: Int?): Cursor {
        val context = providerContext()
        val rowLimit = (limit ?: DEFAULT_DELIVERY_LIMIT).coerceIn(0, MAX_DELIVERY_LIMIT)
        val rows = mutableListOf<Array<Any?>>()
        relayUserIds(context).forEach { userId ->
            RelayStore(context, userId).deliveryEntries().take(rowLimit).forEach { entry ->
                rows +=
                    arrayOf<Any?>(
                        userId,
                        entry.timestamp,
                        entry.appId,
                        entry.kind,
                        if (entry.delivered) 1 else 0,
                        entry.detail,
                    )
            }
        }
        rows.sortByDescending { it[TIMESTAMP_INDEX] as Long }
        return MatrixCursor(DELIVERY_COLUMNS).apply {
            rows.take(rowLimit).forEach { addRow(it) }
        }
    }

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) {
            return
        }
        throw SecurityException("UID $uid is not allowed to manage PushCompat")
    }

    private fun enforceRootCaller() {
        val uid = Binder.getCallingUid()
        if (uid != Process.ROOT_UID) {
            throw SecurityException("UID $uid is not allowed to run native listener diagnostics")
        }
    }

    private fun providerContext(): Context = checkNotNull(context)

    companion object {
        private val APP_COLUMNS =
            arrayOf(
                PushCompatContract.COLUMN_USER_ID,
                PushCompatContract.COLUMN_PACKAGE_NAME,
                PushCompatContract.COLUMN_LABEL,
                PushCompatContract.COLUMN_ENABLED,
                PushCompatContract.COLUMN_ACTIVE,
                PushCompatContract.COLUMN_AVAILABLE,
            )
        private val DELIVERY_COLUMNS =
            arrayOf(
                PushCompatContract.COLUMN_USER_ID,
                PushCompatContract.COLUMN_TIMESTAMP,
                PushCompatContract.COLUMN_APP_ID,
                PushCompatContract.COLUMN_KIND,
                PushCompatContract.COLUMN_DELIVERED,
                PushCompatContract.COLUMN_DETAIL,
            )
        private val UNIFIED_PUSH_COLUMNS =
            arrayOf(
                PushCompatContract.COLUMN_USER_ID,
                PushCompatContract.COLUMN_PACKAGE_NAME,
            )
        private const val TIMESTAMP_INDEX = 1
        private val USER_SCOPED_METHODS =
            setOf(
                PushCompatContract.METHOD_SET_APP_ENABLED,
                METHOD_DEBUG_RUN_MCS,
            )
        private const val DEFAULT_DELIVERY_LIMIT = 12
        private const val MAX_DELIVERY_LIMIT = 50
        private const val METHOD_DEBUG_CHECK_IN = "debugCheckIn"
        private const val METHOD_DEBUG_RUN_MCS = "debugRunMcs"
        private const val KEY_DEBUG_ANDROID_ID = "android_id"
        private const val KEY_DEBUG_FCM_TOKEN = "fcm_token"
        private const val TAG = "PushCompat"
        private val DEBUG_MCS_LOCK = Any()

        @Volatile
        private var activeDebugMcsTransport: OnDeviceMcsTransport? = null

        @Volatile
        private var activeDebugMcsPackage: String? = null

        fun notifyStatus(context: Context) {
            notifyChanged(context, PushCompatContract.STATUS_URI)
        }

        fun notifyApps(context: Context) {
            notifyChanged(context, PushCompatContract.APPS_URI)
        }

        fun notifyUnifiedPush(context: Context) {
            notifyChanged(context, PushCompatContract.UNIFIED_PUSH_URI)
        }

        fun notifyDeliveries(context: Context) {
            notifyChanged(context, PushCompatContract.DELIVERIES_URI)
        }

        private fun notifyChanged(context: Context, uri: Uri) {
            context.contentResolver.notifyChange(uri, null)
        }

        private fun successResult(): Bundle =
            Bundle().apply { putInt(PushCompatContract.KEY_ERROR, PushCompatContract.ERROR_NONE) }

        private fun errorResult(error: Int, message: String?): Bundle =
            Bundle().apply {
                putInt(PushCompatContract.KEY_ERROR, error)
                putString(PushCompatContract.KEY_MESSAGE, message)
            }
    }
}
