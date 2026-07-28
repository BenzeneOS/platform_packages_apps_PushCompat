package com.benzeneos.pushcompat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.UserHandle
import com.android.internal.pushcompat.FirebaseReceiverResolver
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class FirebaseTarget(
    val packageName: String,
    val label: String,
    val isAppEnabled: Boolean,
    val receiverName: String,
    val firebaseAppId: String?,
    val firebaseProjectId: String?,
    val firebaseApiKey: String?,
    val senderId: String?,
    val certificateSha1: String?,
    val versionCode: Int?,
    val versionName: String?,
    val targetSdk: Int?,
) {
    val isReady: Boolean
        get() = !firebaseAppId.isNullOrEmpty() &&
            !firebaseProjectId.isNullOrEmpty() &&
            !firebaseApiKey.isNullOrEmpty() &&
            !certificateSha1.isNullOrEmpty()
}

internal fun Context.asUser(userId: Int): Context =
    if (userId == this.userId) this else createContextAsUser(UserHandle.of(userId), 0)

object FirebaseDiscovery {
    private const val ACTION_C2DM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE"

    fun discover(
        context: Context,
        userId: Int,
    ): List<FirebaseTarget> {
        val packageManager = context.asUser(userId).packageManager
        val receivers = packageManager.queryBroadcastReceivers(
            Intent(ACTION_C2DM_RECEIVE),
            PackageManager.ResolveInfoFlags.of(
                PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
            ),
        )
        return receivers
            .groupBy { it.activityInfo.packageName }
            .mapNotNull { (packageName, candidates) ->
                buildTarget(packageManager, packageName, candidates)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun find(
        context: Context,
        packageName: String,
        userId: Int,
    ): FirebaseTarget? {
        val packageManager = context.asUser(userId).packageManager
        val intent = Intent(ACTION_C2DM_RECEIVE).setPackage(packageName)
        val receivers = packageManager.queryBroadcastReceivers(
            intent,
            PackageManager.ResolveInfoFlags.of(
                PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
            ),
        )
        return buildTarget(packageManager, packageName, receivers)
    }

    private fun buildTarget(
        packageManager: PackageManager,
        packageName: String,
        candidates: List<ResolveInfo>,
    ): FirebaseTarget? {
        val receiver = FirebaseReceiverResolver.selectReceiver(candidates) ?: return null
        val packageInfo = runCatching {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }.getOrNull() ?: return null
        val applicationInfo = packageInfo.applicationInfo ?: return null
        val resources = runCatching {
            packageManager.getResourcesForApplication(packageName)
        }.getOrNull() ?: return null
        val resourcePackage =
            (applicationInfo.labelRes.takeIf { it != 0 } ?: applicationInfo.icon)
                .takeIf { it != 0 }
                ?.let { runCatching { resources.getResourcePackageName(it) }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: packageName

        fun resourceString(name: String): String? {
            val identifier = resources.getIdentifier(name, "string", resourcePackage)
            if (identifier == 0) {
                return null
            }
            return runCatching { resources.getString(identifier) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }

        return FirebaseTarget(
            packageName = packageName,
            label = packageManager.getApplicationLabel(applicationInfo).toString(),
            isAppEnabled = applicationInfo.enabled,
            receiverName = receiver.activityInfo.name,
            firebaseAppId = resourceString("google_app_id"),
            firebaseProjectId = resourceString("project_id"),
            firebaseApiKey = resourceString("google_api_key"),
            senderId = resourceString("gcm_defaultSenderId"),
            certificateSha1 = signingCertificateSha1(packageInfo),
            versionCode = packageInfo.longVersionCode.toInt(),
            versionName = packageInfo.versionName,
            targetSdk = applicationInfo.targetSdkVersion,
        )
    }

    private fun signingCertificateSha1(packageInfo: PackageInfo): String? {
        val signingInfo = packageInfo.signingInfo ?: return null
        val certificate =
            signingInfo.apkContentsSigners.firstOrNull()?.toByteArray() ?: return null
        return MessageDigest.getInstance("SHA-1")
            .digest(certificate)
            .joinToString(separator = "") { "%02x".format(it) }
    }
}

data class FirebaseRegistrationResult(
    val fcmToken: String,
)

interface FirebaseRegistrationClient {
    fun register(target: FirebaseTarget): FirebaseRegistrationResult

    fun unregister(packageName: String)
}

class BridgeClient(
    private val store: RelayStore,
) : FirebaseRegistrationClient {
    val baseUrl: BridgeUrl = store.bridgeUrl

    fun isCurrent(): Boolean = store.bridgeUrl == baseUrl

    override fun register(target: FirebaseTarget): FirebaseRegistrationResult {
        check(target.isReady)
        val body = JSONObject()
            .put("app_id", target.packageName)
            .put("install_id", store.installId)
            .put("transport", "websocket")
            .put("firebase_app_id", target.firebaseAppId)
            .put("firebase_project_id", target.firebaseProjectId)
            .put("firebase_api_key", target.firebaseApiKey)
            .put("cert_sha1", target.certificateSha1)
        target.versionCode?.let { body.put("app_version", it) }
        target.versionName?.let { body.put("app_version_name", it) }
        target.targetSdk?.let { body.put("target_sdk", it) }

        val response = postJson("/register", body)
        val token =
            response.optString("fcm_token").takeIf { it.isNotEmpty() }
                ?: error("bridge did not return an FCM token")
        return FirebaseRegistrationResult(token)
    }

    override fun unregister(packageName: String) {
        postJson(
            "/unregister",
            JSONObject()
                .put("app_id", packageName)
                .put("install_id", store.installId),
        )
    }

    fun registerUnifiedPush(
        appId: String,
        connectorToken: String,
        vapid: String?,
    ): String {
        val body =
            JSONObject()
                .put("install_id", store.installId)
                .put("app_id", appId)
                .put("connector_token", connectorToken)
        vapid?.let { body.put("vapid", it) }
        val response =
            postJson(
                "/distributor/register",
                body,
                connectTimeoutMillis = DISTRIBUTOR_CONNECT_TIMEOUT_MILLIS,
                readTimeoutMillis = DISTRIBUTOR_READ_TIMEOUT_MILLIS,
            )
        val endpointToken = response.getString("endpoint_token")
        return "${baseUrl.value}/up/$endpointToken"
    }

    fun reconcileUnifiedPush(
        registrations: List<NativeRegistration>,
    ): Map<String, String> {
        val entries = JSONArray()
        for (registration in registrations) {
            val entry =
                JSONObject()
                    .put("app_id", registration.appId)
                    .put("connector_token", registration.connectorToken)
            registration.vapid?.let { entry.put("vapid", it) }
            entries.put(entry)
        }
        val response =
            postJson(
                "/distributor/reconcile",
                JSONObject()
                    .put("install_id", store.installId)
                    .put("registrations", entries),
                connectTimeoutMillis = DISTRIBUTOR_CONNECT_TIMEOUT_MILLIS,
                readTimeoutMillis = DISTRIBUTOR_READ_TIMEOUT_MILLIS,
            )
        val endpoints = response.getJSONArray("endpoints")
        val resolved = mutableMapOf<String, String>()
        for (index in 0 until endpoints.length()) {
            val endpoint = endpoints.getJSONObject(index)
            val connectorToken = endpoint.getString("connector_token")
            val endpointToken = endpoint.getString("endpoint_token")
            resolved[connectorToken] = "${baseUrl.value}/up/$endpointToken"
        }
        return resolved
    }

    private fun postJson(
        path: String,
        body: JSONObject,
        connectTimeoutMillis: Int = 15_000,
        readTimeoutMillis: Int = 20_000,
    ): JSONObject =
        postBridgeJson(
            baseUrl,
            store.installSecret,
            path,
            body,
            connectTimeoutMillis,
            readTimeoutMillis,
        )

    companion object {
        private const val DISTRIBUTOR_CONNECT_TIMEOUT_MILLIS = 3_000
        private const val DISTRIBUTOR_READ_TIMEOUT_MILLIS = 4_000
    }
}

// Shared with the removed-profile cleanup, which has a tombstone's credentials
// rather than a live RelayStore.
internal fun postBridgeJson(
    baseUrl: BridgeUrl,
    installSecret: String,
    path: String,
    body: JSONObject,
    connectTimeoutMillis: Int = 15_000,
    readTimeoutMillis: Int = 20_000,
): JSONObject {
    val connection = URL("${baseUrl.value}$path").openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.setRequestProperty("Authorization", "Bearer $installSecret")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(body.toString())
        }
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (responseCode !in 200..299) {
            error("bridge returned HTTP $responseCode: ${responseBody.take(256)}")
        }
        return JSONObject(responseBody)
    } finally {
        connection.disconnect()
    }
}

internal fun RemoteCleanup.unregisterAll() {
    for (packageName in packages) {
        postBridgeJson(
            bridgeUrl,
            installSecret,
            "/unregister",
            JSONObject()
                .put("app_id", packageName)
                .put("install_id", installId),
        )
    }
    // The device never calls /distributor/unregister. Reconcile is authoritative
    // server-side, so an empty list retires every UnifiedPush endpoint at once.
    postBridgeJson(
        bridgeUrl,
        installSecret,
        "/distributor/reconcile",
        JSONObject()
            .put("install_id", installId)
            .put("registrations", JSONArray()),
    )
}

class OnDeviceRegistrationClient(
    private val store: RelayStore,
) : FirebaseRegistrationClient {
    override fun register(target: FirebaseTarget): FirebaseRegistrationResult {
        check(target.isReady)
        val credentialsJson = target.nativeCredentialsJson()
        val sessionJson = NativeListener.checkIn()
        val registrationJson = NativeListener.register(sessionJson, credentialsJson)
        val token =
            JSONObject(registrationJson)
                .optString("fcm_token")
                .takeIf { it.isNotEmpty() }
                ?: error("native registration did not return an FCM token")
        store.saveOnDeviceRegistration(
            OnDeviceRegistration(
                appId = target.packageName,
                sessionJson = sessionJson,
                registrationJson = registrationJson,
                credentialsJson = credentialsJson,
                fcmToken = token,
                persistentIds = emptyList(),
            ),
        )
        return FirebaseRegistrationResult(token)
    }

    override fun unregister(packageName: String) {
        store.removeOnDeviceRegistration(packageName)
    }

    fun currentRegistration(target: FirebaseTarget): OnDeviceRegistration? {
        val credentialsJson = target.nativeCredentialsJson()
        return store.onDeviceRegistrationFor(target.packageName)
            ?.takeIf { it.credentialsJson == credentialsJson }
    }
}

fun FirebaseTarget.nativeCredentialsJson(): String {
    val resolvedSenderId =
        senderId
            ?: firebaseAppId
                ?.split(':')
                ?.takeIf { parts -> parts.size >= 2 && parts[0] == "1" }
                ?.get(1)
            ?: error("Firebase sender ID is missing for $packageName")
    return JSONObject()
        .put("sender_id", resolvedSenderId)
        .put("api_key", firebaseApiKey)
        .put("app_id", firebaseAppId)
        .put("project_id", firebaseProjectId)
        .put("package_name", packageName)
        .put("cert_sha1", certificateSha1)
        .apply {
            versionCode?.let { put("app_version", it) }
            versionName?.let { put("app_version_name", it) }
            targetSdk?.let { put("target_sdk", it) }
        }.toString()
}
