package com.benzeneos.pushcompat

import android.content.Context
import android.content.SharedPreferences
import android.os.UserHandle
import android.os.UserManager
import android.util.Slog
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom

@JvmInline
value class BridgeUrl private constructor(
    val value: String,
) {
    companion object {
        fun parse(value: String): BridgeUrl? {
            val normalized = value.trim().trimEnd('/')
            val uri = runCatching { URI(normalized) }.getOrNull()
            return if (
                uri?.scheme == "https" &&
                !uri.host.isNullOrEmpty() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                (uri.port == -1 || uri.port in 1..65535)
            ) {
                BridgeUrl(normalized)
            } else {
                null
            }
        }
    }
}

data class NativeRegistration(
    val appId: String,
    val connectorToken: String,
    val endpoint: String,
    val vapid: String?,
)

data class NativeRegistrationSnapshot(
    val registrations: List<NativeRegistration>,
    val malformedCount: Int,
)

data class OnDeviceRegistration(
    val appId: String,
    val sessionJson: String,
    val registrationJson: String,
    val credentialsJson: String,
    val fcmToken: String,
    val persistentIds: List<String>,
) {
    fun identity(userId: Int): OnDeviceMcsIdentity =
        OnDeviceMcsIdentity(
            userId = userId,
            appId = appId,
            sessionJson = sessionJson,
            registrationJson = registrationJson,
            persistentIds = persistentIds,
        )
}

// Unregisters a removed profile's packages after its own state is gone.
data class RemoteCleanup(
    val bridgeUrl: BridgeUrl,
    val installId: String,
    val installSecret: String,
    val packages: Set<String>,
)

data class DeliveryEntry(
    val timestamp: Long,
    val appId: String,
    val kind: String,
    val delivered: Boolean,
    val detail: String,
)

// Must be called from the owner: getUserProfiles() throws on a createContextAsUser context.
internal fun relayUserIds(context: Context): List<Int> {
    val userManager = context.getSystemService(UserManager::class.java)
    return userManager.userProfiles
        .filterNot { userManager.isQuietModeEnabled(it) }
        // Keep locked profiles detached so the bridge queues their messages until unlock.
        .filter { userManager.isUserUnlocked(it) }
        .map { it.identifier }
        .sorted()
}

internal fun existingProfileIds(context: Context): Set<Int> =
    context
        .getSystemService(UserManager::class.java)
        .userProfiles
        .mapTo(mutableSetOf()) { it.identifier }

// One process owns every user's state, so the single preference file is partitioned by
// user. The owner keeps unprefixed keys, which is what makes its live identity survive.
class RelayStore(
    context: Context,
    val userId: Int,
) {
    private val context = context.applicationContext
    private val preferences: SharedPreferences =
        this.context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        discardStateFromAPreviousProfile()
    }

    private fun key(name: String): String =
        if (userId == UserHandle.USER_SYSTEM) name else "u$userId|$name"

    // Android reuses a removed profile's id after a reboot; serial numbers it
    // never reuses, and returns -1 for a profile that is gone (purgeRemovedUsers'
    // job, and it needs the state intact).
    private fun discardStateFromAPreviousProfile() {
        if (userId == UserHandle.USER_SYSTEM) {
            return
        }
        val serial =
            context
                .getSystemService(UserManager::class.java)
                .getUserSerialNumber(userId)
        if (serial == UNKNOWN_SERIAL) {
            return
        }
        synchronized(STORE_LOCK) {
            val recorded = preferences.getInt(key(KEY_USER_SERIAL), UNKNOWN_SERIAL)
            if (recorded == serial) {
                return@synchronized
            }
            if (preferences.all.keys.any { it.startsWith("u$userId|") }) {
                Slog.w(TAG, "Discarding relay state for u$userId not stamped with serial $serial")
                removeOwnKeys()
            }
            preferences.edit().putInt(key(KEY_USER_SERIAL), serial).apply()
        }
    }

    private fun removeOwnKeys() {
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith("u$userId|") }
            .forEach(editor::remove)
        editor.apply()
    }

    private fun cursorKey(bridgeUrl: BridgeUrl): String = key("$KEY_CURSOR_PREFIX${bridgeUrl.value}")

    private fun registrationTimeKey(bridgeUrl: BridgeUrl): String =
        key("$KEY_LAST_REGISTRATION_PREFIX${bridgeUrl.value}")

    private fun remotePackagesKey(bridgeUrl: BridgeUrl): String =
        key("$KEY_REMOTE_PACKAGES_PREFIX${bridgeUrl.value}")

    private fun modeHandoffKey(packageName: String): String =
        key("$KEY_MODE_HANDOFF_PREFIX$packageName")

    private fun fcmTokenKey(
        bridgeUrl: BridgeUrl,
        packageName: String,
    ): String = key("$KEY_FCM_TOKEN_PREFIX${bridgeUrl.value}\n$packageName")

    private fun onDeviceRegistrationKey(packageName: String): String =
        key("$KEY_ON_DEVICE_REGISTRATION_PREFIX$packageName")

    var bridgeUrl: BridgeUrl
        get() =
            checkNotNull(
                BridgeUrl.parse(
                    preferences.getString(KEY_BRIDGE_URL, DEFAULT_BRIDGE_URL)
                        ?: DEFAULT_BRIDGE_URL,
                ),
            ) { "Stored bridge URL is invalid" }
        set(value) {
            preferences.edit().putString(KEY_BRIDGE_URL, value.value).apply()
        }

    val installId: String
        get() = getOrCreateSecret(key(KEY_INSTALL_ID), 16)

    val installSecret: String
        get() = getOrCreateSecret(key(KEY_INSTALL_SECRET), 32)

    fun cursorFor(bridgeUrl: BridgeUrl): Long =
        synchronized(STORE_LOCK) {
            preferences.getLong(cursorKey(bridgeUrl), 0L)
        }

    fun advanceCursor(
        bridgeUrl: BridgeUrl,
        value: Long,
    ) {
        synchronized(STORE_LOCK) {
            val key = cursorKey(bridgeUrl)
            if (value > preferences.getLong(key, 0L)) {
                preferences.edit().putLong(key, value).apply()
            }
        }
    }

    fun resetCursor(
        bridgeUrl: BridgeUrl,
        value: Long,
    ) {
        synchronized(STORE_LOCK) {
            preferences.edit().putLong(cursorKey(bridgeUrl), value).apply()
        }
    }

    fun lastRegistrationMillisFor(bridgeUrl: BridgeUrl): Long = preferences.getLong(registrationTimeKey(bridgeUrl), 0L)

    fun setLastRegistrationMillis(
        bridgeUrl: BridgeUrl,
        value: Long,
    ) {
        preferences.edit().putLong(registrationTimeKey(bridgeUrl), value).apply()
    }

    fun markRegistrationDue() {
        setLastRegistrationMillis(bridgeUrl, 0L)
    }

    fun isSocketV2Prepared(): Boolean = preferences.getBoolean(key(KEY_SOCKET_V2_PREPARED), false)

    fun markSocketV2Prepared() {
        preferences.edit().putBoolean(key(KEY_SOCKET_V2_PREPARED), true).apply()
    }

    fun enabledPackages(): Set<String> = preferences.getStringSet(key(KEY_ENABLED_PACKAGES), emptySet())?.toSet() ?: emptySet()

    fun setPackageEnabled(
        packageName: String,
        enabled: Boolean,
    ) {
        synchronized(STORE_LOCK) {
            val packages = enabledPackages().toMutableSet()
            if (enabled) {
                packages.add(packageName)
            } else {
                packages.remove(packageName)
            }
            preferences.edit().putStringSet(key(KEY_ENABLED_PACKAGES), packages).apply()
        }
    }

    // Delivery mode is device-global — one switch for every profile and app.
    @DeliveryMode
    var deliveryMode: Int
        get() =
            if (
                preferences.getInt(KEY_DELIVERY_MODE, PushCompatContract.DELIVERY_MODE_BRIDGE) ==
                PushCompatContract.DELIVERY_MODE_ON_DEVICE
            ) {
                PushCompatContract.DELIVERY_MODE_ON_DEVICE
            } else {
                PushCompatContract.DELIVERY_MODE_BRIDGE
            }
        set(value) {
            check(
                value == PushCompatContract.DELIVERY_MODE_BRIDGE ||
                value == PushCompatContract.DELIVERY_MODE_ON_DEVICE,
            )
            preferences.edit().putInt(KEY_DELIVERY_MODE, value).apply()
        }

    fun modeHandoffReadyAt(
        packageName: String,
        @DeliveryMode mode: Int,
    ): Long? {
        val encoded = preferences.getString(modeHandoffKey(packageName), null) ?: return null
        return runCatching {
            val handoff = JSONObject(encoded)
            handoff
                .takeIf { it.getInt("mode") == mode }
                ?.getLong("ready_at")
        }.getOrNull()
    }

    fun markModeHandoffReady(
        packageName: String,
        @DeliveryMode mode: Int,
        readyAt: Long,
    ) {
        if (modeHandoffReadyAt(packageName, mode) != null) {
            return
        }
        preferences
            .edit()
            .putString(
                modeHandoffKey(packageName),
                JSONObject()
                    .put("mode", mode)
                    .put("ready_at", readyAt)
                    .toString(),
            )
            .apply()
    }

    fun clearModeHandoff(packageName: String) {
        preferences.edit().remove(modeHandoffKey(packageName)).apply()
    }

    fun remotelyRegisteredPackagesFor(bridgeUrl: BridgeUrl): Set<String> =
        preferences.getStringSet(remotePackagesKey(bridgeUrl), emptySet())?.toSet() ?: emptySet()

    fun setRemotelyRegisteredPackages(
        bridgeUrl: BridgeUrl,
        packages: Set<String>,
    ) {
        preferences.edit().putStringSet(remotePackagesKey(bridgeUrl), packages).apply()
    }

    fun frameworkEnabledPackages(): Set<String> = preferences.getStringSet(key(KEY_FRAMEWORK_PACKAGES), emptySet())?.toSet() ?: emptySet()

    fun setFrameworkEnabledPackages(packages: Set<String>) {
        preferences.edit().putStringSet(key(KEY_FRAMEWORK_PACKAGES), packages).apply()
    }

    fun setFcmToken(
        bridgeUrl: BridgeUrl,
        packageName: String,
        token: String,
    ) {
        preferences.edit().putString(fcmTokenKey(bridgeUrl, packageName), token).apply()
    }

    fun fcmTokenFor(
        bridgeUrl: BridgeUrl,
        packageName: String,
    ): String? = preferences.getString(fcmTokenKey(bridgeUrl, packageName), null)

    fun onDeviceRegistrationFor(packageName: String): OnDeviceRegistration? =
        synchronized(STORE_LOCK) {
            decodeOnDeviceRegistration(
                preferences.getString(onDeviceRegistrationKey(packageName), null),
            )
        }

    fun onDeviceRegistrations(): List<OnDeviceRegistration> =
        synchronized(STORE_LOCK) {
            val packages =
                preferences.getStringSet(key(KEY_ON_DEVICE_PACKAGES), emptySet()) ?: emptySet()
            packages.mapNotNull { packageName ->
                decodeOnDeviceRegistration(
                    preferences.getString(onDeviceRegistrationKey(packageName), null),
                )
            }
        }

    fun saveOnDeviceRegistration(registration: OnDeviceRegistration) {
        synchronized(STORE_LOCK) {
            val packages =
                preferences.getStringSet(key(KEY_ON_DEVICE_PACKAGES), emptySet())
                    ?.toMutableSet() ?: mutableSetOf()
            packages.add(registration.appId)
            preferences
                .edit()
                .putString(
                    onDeviceRegistrationKey(registration.appId),
                    encodeOnDeviceRegistration(registration),
                )
                .putStringSet(key(KEY_ON_DEVICE_PACKAGES), packages)
                .apply()
        }
    }

    fun removeOnDeviceRegistration(packageName: String) {
        synchronized(STORE_LOCK) {
            val packages =
                preferences.getStringSet(key(KEY_ON_DEVICE_PACKAGES), emptySet())
                    ?.toMutableSet() ?: mutableSetOf()
            packages.remove(packageName)
            preferences
                .edit()
                .remove(onDeviceRegistrationKey(packageName))
                .putStringSet(key(KEY_ON_DEVICE_PACKAGES), packages)
                .apply()
        }
    }

    fun recordOnDevicePersistentId(
        packageName: String,
        persistentId: String,
    ) {
        synchronized(STORE_LOCK) {
            val registration = onDeviceRegistrationFor(packageName) ?: return
            val persistentIds =
                registration.persistentIds
                    .filterNot { it == persistentId }
                    .plus(persistentId)
                    .takeLast(MAX_PERSISTENT_IDS)
            preferences
                .edit()
                .putString(
                    onDeviceRegistrationKey(packageName),
                    encodeOnDeviceRegistration(
                        registration.copy(persistentIds = persistentIds),
                    ),
                )
                .apply()
        }
    }

    fun nativeRegistrationSnapshot(): NativeRegistrationSnapshot =
        synchronized(STORE_LOCK) {
            val registrations = mutableListOf<NativeRegistration>()
            var malformedCount = 0
            for (value in encodedNativeRegistrations()) {
                val registration = decodeNativeRegistration(value)
                if (registration == null) {
                    malformedCount++
                } else {
                    registrations.add(registration)
                }
            }
            NativeRegistrationSnapshot(registrations, malformedCount)
        }

    fun nativeRegistrations(): List<NativeRegistration> =
        nativeRegistrationSnapshot().registrations

    fun saveNativeRegistration(registration: NativeRegistration) {
        synchronized(STORE_LOCK) {
            val encoded =
                encodedNativeRegistrations().filterTo(mutableSetOf()) { value ->
                    decodeNativeRegistration(value)?.connectorToken != registration.connectorToken
                }
            encoded.add(encodeNativeRegistration(registration))
            saveEncodedNativeRegistrations(encoded)
        }
        ManagementProvider.notifyStatus(context)
        ManagementProvider.notifyUnifiedPush(context)
    }

    fun removeNativeRegistration(
        appId: String,
        connectorToken: String,
    ): Boolean {
        val removed =
            synchronized(STORE_LOCK) {
                val encoded = encodedNativeRegistrations()
                val remaining =
                    encoded.filterTo(mutableSetOf()) { value ->
                        val registration = decodeNativeRegistration(value)
                        registration == null ||
                            registration.appId != appId ||
                            registration.connectorToken != connectorToken
                    }
                if (remaining.size == encoded.size) {
                    return@synchronized false
                }
                saveEncodedNativeRegistrations(remaining)
                true
            }
        if (removed) {
            ManagementProvider.notifyStatus(context)
            ManagementProvider.notifyUnifiedPush(context)
        }
        return removed
    }

    fun findNativeRegistration(
        appId: String,
        connectorToken: String,
    ): NativeRegistration? =
        nativeRegistrations().firstOrNull {
            it.appId == appId && it.connectorToken == connectorToken
        }

    fun addDeliveryEntry(entry: DeliveryEntry) {
        synchronized(STORE_LOCK) {
            val entries = deliveryEntries().toMutableList()
            entries.add(0, entry)
            val array = JSONArray()
            entries.take(MAX_DELIVERY_ENTRIES).forEach { item ->
                array.put(
                    JSONObject()
                        .put("timestamp", item.timestamp)
                        .put("app_id", item.appId)
                        .put("kind", item.kind)
                        .put("delivered", item.delivered)
                        .put("detail", item.detail),
                )
            }
            preferences.edit().putString(key(KEY_DELIVERY_LOG), array.toString()).apply()
        }
        ManagementProvider.notifyDeliveries(context)
    }

    fun knownRelayUserIds(): Set<Int> =
        preferences
            .getStringSet(key(KEY_RELAY_USERS), emptySet())
            .orEmpty()
            .mapNotNullTo(mutableSetOf()) { it.toIntOrNull() }

    fun setKnownRelayUserIds(userIds: Set<Int>) {
        preferences
            .edit()
            .putStringSet(key(KEY_RELAY_USERS), userIds.mapTo(mutableSetOf()) { it.toString() })
            .apply()
    }

    fun purgeAllKeys() {
        check(userId != UserHandle.USER_SYSTEM) { "refusing to purge the owner's state" }
        synchronized(STORE_LOCK) {
            removeOwnKeys()
        }
    }

    // Held under the owner's namespace so a retry never depends on a reusable
    // user id.
    fun pendingRemoteCleanup(): List<RemoteCleanup> =
        synchronized(STORE_LOCK) {
            val encoded =
                preferences.getString(key(KEY_REMOTE_CLEANUP), null)
                    ?: return@synchronized emptyList()
            runCatching {
                val array = JSONArray(encoded)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        val bridgeUrl = BridgeUrl.parse(item.getString("bridge_url")) ?: continue
                        val packages = item.getJSONArray("packages")
                        add(
                            RemoteCleanup(
                                bridgeUrl = bridgeUrl,
                                installId = item.getString("install_id"),
                                installSecret = item.getString("install_secret"),
                                packages =
                                    (0 until packages.length())
                                        .mapTo(mutableSetOf()) { packages.getString(it) },
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

    fun setPendingRemoteCleanup(entries: List<RemoteCleanup>) {
        synchronized(STORE_LOCK) {
            if (entries.isEmpty()) {
                preferences.edit().remove(key(KEY_REMOTE_CLEANUP)).apply()
                return@synchronized
            }
            if (entries.size > MAX_REMOTE_CLEANUP_ENTRIES) {
                Slog.w(
                    TAG,
                    "Dropping ${entries.size - MAX_REMOTE_CLEANUP_ENTRIES} pending remote " +
                        "cleanups, leaving those bridge rows to the server prune",
                )
            }
            val array = JSONArray()
            entries.take(MAX_REMOTE_CLEANUP_ENTRIES).forEach { entry ->
                array.put(
                    JSONObject()
                        .put("bridge_url", entry.bridgeUrl.value)
                        .put("install_id", entry.installId)
                        .put("install_secret", entry.installSecret)
                        .put("packages", JSONArray(entry.packages.toList())),
                )
            }
            preferences.edit().putString(key(KEY_REMOTE_CLEANUP), array.toString()).apply()
        }
    }

    fun deliveryEntries(): List<DeliveryEntry> =
        synchronized(STORE_LOCK) {
            val encoded =
                preferences.getString(key(KEY_DELIVERY_LOG), null) ?: return@synchronized emptyList()
            runCatching {
                val array = JSONArray(encoded)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(
                            DeliveryEntry(
                                timestamp = item.getLong("timestamp"),
                                appId = item.getString("app_id"),
                                kind = item.getString("kind"),
                                delivered = item.getBoolean("delivered"),
                                detail = item.getString("detail"),
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

    private fun encodedNativeRegistrations(): Set<String> =
        preferences
            .getStringSet(key(KEY_NATIVE_REGISTRATIONS), emptySet())
            ?.toSet()
            ?: emptySet()

    private fun decodeNativeRegistration(value: String): NativeRegistration? =
        runCatching {
            val json = JSONObject(value)
            NativeRegistration(
                appId = json.getString("app_id"),
                connectorToken = json.getString("connector_token"),
                endpoint = json.getString("endpoint"),
                vapid = json.optString("vapid").takeIf { it.isNotEmpty() },
            )
        }.getOrNull()

    private fun encodeNativeRegistration(registration: NativeRegistration): String {
        val json =
            JSONObject()
                .put("app_id", registration.appId)
                .put("connector_token", registration.connectorToken)
                .put("endpoint", registration.endpoint)
        registration.vapid?.let { json.put("vapid", it) }
        return json.toString()
    }

    private fun saveEncodedNativeRegistrations(registrations: Set<String>) {
        preferences.edit().putStringSet(key(KEY_NATIVE_REGISTRATIONS), registrations).apply()
    }

    private fun decodeOnDeviceRegistration(encoded: String?): OnDeviceRegistration? =
        encoded?.let { value ->
            runCatching {
                val json = JSONObject(value)
                val persistentIds = json.optJSONArray("persistent_ids") ?: JSONArray()
                OnDeviceRegistration(
                    appId = json.getString("app_id"),
                    sessionJson = json.getString("session"),
                    registrationJson = json.getString("registration"),
                    credentialsJson = json.getString("credentials"),
                    fcmToken = json.getString("fcm_token"),
                    persistentIds =
                        buildList {
                            for (index in 0 until persistentIds.length()) {
                                add(persistentIds.getString(index))
                            }
                        },
                )
            }.getOrNull()
        }

    private fun encodeOnDeviceRegistration(registration: OnDeviceRegistration): String =
        JSONObject()
            .put("app_id", registration.appId)
            .put("session", registration.sessionJson)
            .put("registration", registration.registrationJson)
            .put("credentials", registration.credentialsJson)
            .put("fcm_token", registration.fcmToken)
            .put("persistent_ids", JSONArray(registration.persistentIds))
            .toString()

    private fun getOrCreateSecret(
        key: String,
        byteCount: Int,
    ): String {
        synchronized(STORE_LOCK) {
            preferences.getString(key, null)?.let { return it }
            val bytes = ByteArray(byteCount)
            SecureRandom().nextBytes(bytes)
            val value = bytes.joinToString(separator = "") { "%02x".format(it) }
            check(preferences.edit().putString(key, value).commit()) {
                "Failed to persist relay identity"
            }
            return value
        }
    }

    companion object {
        const val DEFAULT_BRIDGE_URL = "https://push.benzeneos.org"
        const val REGISTRATION_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

        private const val PREFERENCES_NAME = "pushcompat"
        private const val KEY_BRIDGE_URL = "bridge_url"
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_INSTALL_SECRET = "install_secret"
        private const val KEY_CURSOR_PREFIX = "cursor:"
        private const val KEY_SOCKET_V2_PREPARED = "socket_v2_prepared"
        private const val KEY_LAST_REGISTRATION_PREFIX = "last_registration:"
        private const val KEY_ENABLED_PACKAGES = "enabled_packages"
        private const val KEY_DELIVERY_MODE = "delivery_mode"
        private const val KEY_MODE_HANDOFF_PREFIX = "mode_handoff:"
        private const val KEY_REMOTE_PACKAGES_PREFIX = "remote_packages:"
        private const val KEY_FRAMEWORK_PACKAGES = "framework_packages"
        private const val KEY_FCM_TOKEN_PREFIX = "fcm_token:"
        private const val KEY_ON_DEVICE_PACKAGES = "on_device_packages"
        private const val KEY_ON_DEVICE_REGISTRATION_PREFIX = "on_device_registration:"
        private const val KEY_NATIVE_REGISTRATIONS = "native_registrations"
        private const val KEY_DELIVERY_LOG = "delivery_log"
        private const val KEY_RELAY_USERS = "relay_users"
        private const val KEY_USER_SERIAL = "user_serial"
        private const val KEY_REMOTE_CLEANUP = "remote_cleanup"
        private const val MAX_DELIVERY_ENTRIES = 50
        private const val MAX_PERSISTENT_IDS = 500

        // An unreachable bridge must not grow this forever. The dropped tail is
        // left to the server's 90-day prune.
        private const val MAX_REMOTE_CLEANUP_ENTRIES = 32
        private const val TAG = "PushCompat"
        private const val UNKNOWN_SERIAL = -1
        private val STORE_LOCK = Object()
    }
}
