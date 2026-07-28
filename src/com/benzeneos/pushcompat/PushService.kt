package com.benzeneos.pushcompat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.SystemClock
import android.os.UserHandle
import android.util.Slog
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

data class PushSocketHealth(
    @SocketState val state: Int,
    val stateSinceMillis: Long,
    val connectedSinceMillis: Long,
    val consecutiveConnectFailures: Int,
    val lastError: String?,
)

class PushService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val retryScheduler = Executors.newSingleThreadScheduledExecutor()
    private val wakeMonitor = Object()

    @Volatile
    private var destroyed = false

    @Volatile
    private var workerRunning = false

    @Volatile
    private var reconnectRequested = false

    @Volatile
    private var refreshRequested = false

    @Volatile
    private var networkReconnectRequested = false

    @Volatile
    private var activeTransport: Closeable? = null

    private val packageChangeReceiver = PackageChangeReceiver()
    private val profileChangeReceiver = ProfileChangeReceiver()
    private val networkStateLock = Any()
    private var lastNetworkHandle: Long? = null
    private var lastNetworkChangeElapsedMillis = 0L
    private var validatedNetwork = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered = false
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val networkChanged =
                    synchronized(networkStateLock) {
                        if (lastNetworkHandle == network.networkHandle) {
                            false
                        } else {
                            lastNetworkHandle = network.networkHandle
                            lastNetworkChangeElapsedMillis = SystemClock.elapsedRealtime()
                            validatedNetwork = false
                            true
                        }
                    }
                if (networkChanged && !destroyed) {
                    executeNetworkChange {
                        clearSocketFailuresAfterNetworkChange()
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                val (networkChanged, becameValidated) =
                    synchronized(networkStateLock) {
                        var networkChanged = false
                        if (lastNetworkHandle != network.networkHandle) {
                            lastNetworkHandle = network.networkHandle
                            lastNetworkChangeElapsedMillis = SystemClock.elapsedRealtime()
                            validatedNetwork = false
                            networkChanged = true
                        }
                        val isValidated =
                            networkCapabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                            )
                        val becameValidated = isValidated && !validatedNetwork
                        if (isValidated != validatedNetwork) {
                            lastNetworkChangeElapsedMillis = SystemClock.elapsedRealtime()
                            networkChanged = true
                        }
                        validatedNetwork = isValidated
                        networkChanged to becameValidated
                    }
                if (networkChanged && !destroyed) {
                    executeNetworkChange {
                        clearSocketFailuresAfterNetworkChange()
                        if (becameValidated) {
                            requestRelay(resetBackoff = true)
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                val defaultNetworkLost =
                    synchronized(networkStateLock) {
                        if (lastNetworkHandle != network.networkHandle) {
                            false
                        } else {
                            lastNetworkHandle = null
                            lastNetworkChangeElapsedMillis = SystemClock.elapsedRealtime()
                            validatedNetwork = false
                            true
                        }
                    }
                if (defaultNetworkLost && !destroyed) {
                    executeNetworkChange {
                        clearSocketFailuresAfterNetworkChange()
                        updateSocketState(PushCompatContract.SOCKET_CONNECTING)
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            relayNotification(getString(R.string.relay_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        registerReceiverForAllUsers(
            packageChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addAction(Intent.ACTION_PACKAGE_UNSTOPPED)
                addDataScheme("package")
            },
            null,
            null,
        )
        registerReceiver(
            profileChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_PROFILE_ACCESSIBLE)
                addAction(Intent.ACTION_PROFILE_INACCESSIBLE)
                addAction(Intent.ACTION_PROFILE_ADDED)
                addAction(Intent.ACTION_PROFILE_REMOVED)
            },
        )
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        connectivityManager?.let { manager ->
            manager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
        updateSocketState(PushCompatContract.SOCKET_CONNECTING)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        requestRelay()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        if (networkCallbackRegistered) {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
        unregisterReceiver(packageChangeReceiver)
        unregisterReceiver(profileChangeReceiver)
        activeTransport?.close()
        updateSocketState(PushCompatContract.SOCKET_IDLE)
        synchronized(wakeMonitor) {
            wakeMonitor.notifyAll()
        }
        retryScheduler.shutdownNow()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runRelay() {
        var reconnectDelayMillis = INITIAL_RECONNECT_MILLIS
        var knownStores = emptyList<RelayStore>()
        try {
            while (!destroyed) {
                val resetBackoffForNetwork =
                    synchronized(wakeMonitor) {
                        val reset = networkReconnectRequested
                        reconnectRequested = false
                        refreshRequested = false
                        networkReconnectRequested = false
                        reset
                    }
                if (resetBackoffForNetwork) {
                    reconnectDelayMillis = INITIAL_RECONNECT_MILLIS
                }
                purgeRemovedUsers()
                val reconciliations = relayUserIds(this).map(::reconcileUser)
                if (reconnectRequested) {
                    continue
                }
                knownStores = reconciliations.map { it.store }
                var appsChanged = false
                for (reconciliation in reconciliations) {
                    val previousActivePackages =
                        reconciliation.store.frameworkEnabledPackages()
                    RelayRegistry.syncFrameworkFlags(
                        reconciliation.store,
                        reconciliation.activePackages,
                    )
                    if (previousActivePackages !=
                        reconciliation.store.frameworkEnabledPackages()
                    ) {
                        appsChanged = true
                    }
                }
                if (appsChanged) {
                    ManagementProvider.notifyApps(this)
                }
                ManagementProvider.notifyStatus(this)
                val retryNeeded = reconciliations.any { it.retryNeeded }
                val bridgeSessions =
                    reconciliations
                        .filter { it.bridgeWork }
                        .map { BridgeSocketSession(it.userId, it.store) }
                val onDeviceIdentities = reconciliations.flatMap { it.onDeviceIdentities }
                val localWork = bridgeSessions.isNotEmpty() || onDeviceIdentities.isNotEmpty()
                if (!localWork && !retryNeeded) {
                    updateSocketState(PushCompatContract.SOCKET_IDLE)
                    stopSelf()
                    return
                }
                if (retryNeeded && !localWork) {
                    updateSocketState(PushCompatContract.SOCKET_CONNECTING)
                    updateNotification(getString(R.string.relay_connecting))
                    waitForReconcile(reconnectDelayMillis)
                    reconnectDelayMillis =
                        if (reconnectRequested) {
                            INITIAL_RECONNECT_MILLIS
                        } else {
                            min(reconnectDelayMillis * 2, MAX_RECONNECT_MILLIS)
                        }
                    continue
                }

                updateSocketState(PushCompatContract.SOCKET_CONNECTING)
                updateNotification(getString(R.string.relay_connecting))
                val connectedAt = System.currentTimeMillis()
                try {
                    runTransports(
                        reconciliations = reconciliations,
                        bridgeSessions = bridgeSessions,
                        onDeviceIdentities = onDeviceIdentities,
                        retryNeeded = retryNeeded,
                        reconnectDelayMillis = reconnectDelayMillis,
                    )
                    if (!destroyed && !reconnectRequested && !refreshRequested) {
                        recordSocketFailure(null)
                        Slog.w(TAG, "Push socket disconnected")
                    }
                } catch (e: Exception) {
                    reconciliations.forEach {
                        it.store.setLastRegistrationMillis(it.store.bridgeUrl, 0L)
                    }
                    if (!destroyed && !reconnectRequested && !refreshRequested) {
                        recordSocketFailure(e)
                        Slog.w(TAG, "Push socket disconnected", e)
                    }
                } finally {
                    activeTransport = null
                    if (!destroyed) {
                        updateSocketState(PushCompatContract.SOCKET_CONNECTING)
                    }
                }

                if (destroyed) {
                    return
                }
                val resetBackoff =
                    reconnectRequested ||
                        (
                            !refreshRequested &&
                                System.currentTimeMillis() - connectedAt >=
                                STABLE_CONNECTION_MILLIS
                        )
                if (resetBackoff) {
                    reconnectDelayMillis = INITIAL_RECONNECT_MILLIS
                }
                waitForReconcile(reconnectDelayMillis)
                if (!resetBackoff) {
                    reconnectDelayMillis =
                        min(
                            reconnectDelayMillis * 2,
                            MAX_RECONNECT_MILLIS,
                        )
                }
            }
        } finally {
            var appsChanged = false
            for (store in knownStores) {
                val previousActivePackages = store.frameworkEnabledPackages()
                RelayRegistry.syncFrameworkFlags(store, emptySet())
                if (previousActivePackages != store.frameworkEnabledPackages()) {
                    appsChanged = true
                }
            }
            if (appsChanged) {
                ManagementProvider.notifyApps(this)
            }
            var restart = false
            synchronized(wakeMonitor) {
                workerRunning = false
                if (!destroyed && (reconnectRequested || refreshRequested)) {
                    workerRunning = true
                    restart = true
                }
            }
            if (restart) {
                updateSocketState(PushCompatContract.SOCKET_CONNECTING)
                executor.execute { runRelay() }
            } else {
                updateSocketState(PushCompatContract.SOCKET_IDLE)
            }
        }
    }

    // Android reuses user IDs, so a removed profile's identities must not linger.
    // Local state goes immediately and the remote unregister retries from an
    // owner-held tombstone, because keeping it under the reusable "u<id>|" prefix
    // is what would hand it to the next profile created with that id.
    private fun purgeRemovedUsers() {
        val ownerStore = RelayStore(this, UserHandle.USER_SYSTEM)
        val existing = existingProfileIds(this)
        val known = ownerStore.knownRelayUserIds()
        val pending = ownerStore.pendingRemoteCleanup().toMutableList()
        for (userId in known - existing) {
            if (userId == UserHandle.USER_SYSTEM) {
                continue
            }
            val store = RelayStore(this, userId)
            val bridgeUrl = store.bridgeUrl
            val packages = store.remotelyRegisteredPackagesFor(bridgeUrl)
            // A profile holding only UnifiedPush registrations has no FCM
            // packages, and unregisterAll still has an empty reconcile to send.
            if (packages.isNotEmpty() || store.nativeRegistrations().isNotEmpty()) {
                pending.add(
                    RemoteCleanup(
                        bridgeUrl = bridgeUrl,
                        installId = store.installId,
                        installSecret = store.installSecret,
                        packages = packages,
                    ),
                )
            }
            store.purgeAllKeys()
            Slog.i(TAG, "Purged relay state for removed user $userId")
        }
        if (known != existing) {
            ownerStore.setKnownRelayUserIds(existing)
        }
        // Written unconditionally. An unreachable bridge fails every entry, and
        // skipping the write when nothing succeeded would discard the tombstones
        // just created for the profiles purged above.
        val unfinished = pending.filter { runCatching { it.unregisterAll() }.isFailure }
        ownerStore.setPendingRemoteCleanup(unfinished)
    }

    private fun runTransports(
        reconciliations: List<UserReconciliation>,
        bridgeSessions: List<BridgeSocketSession>,
        onDeviceIdentities: List<OnDeviceMcsIdentity>,
        retryNeeded: Boolean,
        reconnectDelayMillis: Long,
    ) {
        val byUser = reconciliations.associateBy { it.userId }
        val onMessage: (Int, SocketEnvelope) -> Boolean = { userId, message ->
            val reconciliation = byUser[userId]
            if (reconciliation == null) {
                Slog.e(TAG, "Dropping a push message for the unknown user $userId")
                false
            } else {
                deliverSocketMessage(reconciliation, message)
            }
        }
        val transports =
            buildList<PushTransport> {
                if (bridgeSessions.isNotEmpty()) {
                    add(
                        BridgeSocketTransport(
                            bridgeUrl = RelayStore(this@PushService, UserHandle.USER_SYSTEM).bridgeUrl,
                            sessions = bridgeSessions,
                        ),
                    )
                }
                OnDeviceMcsTransport.partitionByPackage(onDeviceIdentities).forEach { group ->
                    add(
                        OnDeviceMcsTransport(
                            identities = group,
                            onPersistentId = { userId, packageName, persistentId ->
                                byUser[userId]?.store?.recordOnDevicePersistentId(
                                    packageName,
                                    persistentId,
                                )
                            },
                        ),
                    )
                }
            }
        val transportGroup = CloseableGroup(transports)
        activeTransport = transportGroup
        if (reconnectRequested) {
            transportGroup.close()
            return
        }
        val scheduledCloseDelay =
            listOfNotNull(
                reconnectDelayMillis.takeIf { retryNeeded },
                reconciliations.mapNotNull { it.nextReconcileMillis }.minOrNull(),
            ).minOrNull()
        val scheduledClose =
            scheduledCloseDelay?.let { delayMillis ->
                retryScheduler.schedule(
                    {
                        requestRefresh()
                        transportGroup.close()
                    },
                    delayMillis.coerceAtLeast(1L),
                    TimeUnit.MILLISECONDS,
                )
            }
        try {
            runTransportGroup(
                transportGroup = transportGroup,
                runs =
                    transports.map { transport ->
                        {
                            transport.run(
                                onSocketState = ::handleSocketState,
                                onMessage = onMessage,
                            )
                        }
                    },
            )
        } finally {
            scheduledClose?.cancel(false)
            transportGroup.close()
        }
    }

    private fun runTransportGroup(
        transportGroup: CloseableGroup,
        runs: List<() -> Unit>,
    ) {
        if (runs.size == 1) {
            runs.single()()
            return
        }
        val failure = AtomicReference<Exception?>()
        val threads =
            runs.drop(1).mapIndexed { index, run ->
                Thread(
                    {
                        try {
                            run()
                        } catch (e: Exception) {
                            failure.compareAndSet(null, e)
                        } finally {
                            transportGroup.close()
                        }
                    },
                    "PushCompat-transport-${index + 1}",
                )
            }
        threads.forEach { it.start() }
        try {
            runs.first()()
        } catch (e: Exception) {
            failure.compareAndSet(null, e)
        } finally {
            transportGroup.close()
            threads.forEach { it.join() }
        }
        failure.get()?.let { throw it }
    }

    private fun handleSocketState(
        @SocketState state: Int,
    ) {
        updateSocketState(state)
        updateNotification(
            getString(
                if (state == PushCompatContract.SOCKET_CONNECTED) {
                    R.string.relay_running
                } else {
                    R.string.relay_connecting
                },
            ),
        )
    }

    private fun reconcileUser(userId: Int): UserReconciliation {
        val store = RelayStore(this, userId)
        if (!store.isSocketV2Prepared()) {
            store.markRegistrationDue()
        }
        val reconciliation = reconcileRegistrations(userId, store)
        if (!reconciliation.retryNeeded) {
            store.markSocketV2Prepared()
        }
        return reconciliation
    }

    private fun reconcileRegistrations(
        userId: Int,
        store: RelayStore,
    ): UserReconciliation {
        val targetSelection = RelayRegistry.enabledFirebaseTargets(this, store)
        if (targetSelection.unavailablePackages.isNotEmpty()) {
            Slog.e(
                TAG,
                "Cannot register enabled packages with incomplete Firebase resources: " +
                    targetSelection.unavailablePackages.sorted().joinToString(),
            )
        }
        val targets = targetSelection.ready
        val desiredReadyPackages = targets.keys
        val bridgeClient = BridgeClient(store)
        val onDeviceClient = OnDeviceRegistrationClient(store)
        val bridgeUrl = bridgeClient.baseUrl
        val remotePackages = store.remotelyRegisteredPackagesFor(bridgeUrl).toMutableSet()
        var retryNeeded = reconcileNativeRegistrations(userId, store, bridgeClient)
        var nextReconcileMillis: Long? = null

        fun reconcileAfter(delayMillis: Long) {
            nextReconcileMillis =
                nextReconcileMillis
                    ?.coerceAtMost(delayMillis)
                    ?: delayMillis
        }

        fun failedReconciliation(): UserReconciliation =
            UserReconciliation(
                userId = userId,
                store = store,
                activePackages = emptySet(),
                bridgeWork = store.nativeRegistrations().isNotEmpty(),
                onDeviceIdentities = emptyList(),
                retryNeeded = true,
                nextReconcileMillis = null,
            )

        if (!bridgeClient.isCurrent()) {
            return failedReconciliation()
        }

        for (packageName in remotePackages - desiredReadyPackages) {
            try {
                bridgeClient.unregister(packageName)
                if (!bridgeClient.isCurrent()) {
                    return failedReconciliation()
                }
                remotePackages.remove(packageName)
                store.clearModeHandoff(packageName)
            } catch (e: Exception) {
                retryNeeded = true
                Slog.w(TAG, "Failed to unregister $packageName", e)
            }
        }
        for (registration in store.onDeviceRegistrations()) {
            if (registration.appId !in desiredReadyPackages) {
                onDeviceClient.unregister(registration.appId)
                store.clearModeHandoff(registration.appId)
            }
        }

        val registrationDue =
            System.currentTimeMillis() - store.lastRegistrationMillisFor(bridgeUrl) >=
                RelayStore.REGISTRATION_INTERVAL_MILLIS
        var bridgeRegistrationSucceeded = false
        var bridgeRegistrationFailed = false
        for ((packageName, target) in targets) {
            when (store.deliveryMode) {
                PushCompatContract.DELIVERY_MODE_BRIDGE -> {
                    var cachedToken = store.fcmTokenFor(bridgeUrl, packageName)
                    if (
                        packageName !in remotePackages ||
                        cachedToken.isNullOrEmpty() ||
                        registrationDue
                    ) {
                        try {
                            val registration = bridgeClient.register(target)
                            if (!bridgeClient.isCurrent()) {
                                return failedReconciliation()
                            }
                            cachedToken = registration.fcmToken
                            store.setFcmToken(bridgeUrl, packageName, registration.fcmToken)
                            remotePackages.add(packageName)
                            bridgeRegistrationSucceeded = true
                        } catch (e: Exception) {
                            bridgeRegistrationFailed = true
                            if (cachedToken.isNullOrEmpty() || packageName !in remotePackages) {
                                retryNeeded = true
                            }
                            Slog.e(TAG, "Failed to register $packageName with the bridge", e)
                        }
                    }
                    val tokenDelivered =
                        !cachedToken.isNullOrEmpty() &&
                            packageName in remotePackages &&
                            AppDispatcher.deliverToken(userId, packageName, cachedToken)
                    if (!tokenDelivered) {
                        retryNeeded = true
                    }
                    if (tokenDelivered && store.onDeviceRegistrationFor(packageName) != null) {
                        val now = System.currentTimeMillis()
                        store.markModeHandoffReady(
                            packageName,
                            PushCompatContract.DELIVERY_MODE_BRIDGE,
                            now,
                        )
                        val readyAt =
                            store.modeHandoffReadyAt(
                                packageName,
                                PushCompatContract.DELIVERY_MODE_BRIDGE,
                            ) ?: now
                        val remaining = TOKEN_HANDOFF_GRACE_MILLIS - (now - readyAt)
                        if (remaining > 0L) {
                            reconcileAfter(remaining)
                        } else {
                            onDeviceClient.unregister(packageName)
                            store.clearModeHandoff(packageName)
                        }
                    } else if (store.onDeviceRegistrationFor(packageName) == null) {
                        store.clearModeHandoff(packageName)
                    }
                }

                PushCompatContract.DELIVERY_MODE_ON_DEVICE -> {
                    var registration =
                        try {
                            onDeviceClient.currentRegistration(target)
                        } catch (e: Exception) {
                            retryNeeded = true
                            Slog.e(
                                TAG,
                                "Failed to restore on-device registration for $packageName",
                                e,
                            )
                            null
                        }
                    if (registration == null) {
                        try {
                            onDeviceClient.register(target)
                            registration = onDeviceClient.currentRegistration(target)
                        } catch (e: Exception) {
                            retryNeeded = true
                            Slog.e(
                                TAG,
                                "Failed to register $packageName on-device",
                                e,
                            )
                        }
                    }
                    val tokenDelivered =
                        registration?.let {
                            AppDispatcher.deliverToken(userId, packageName, it.fcmToken)
                        } == true
                    if (!tokenDelivered) {
                        retryNeeded = true
                    }
                    if (tokenDelivered && packageName in remotePackages) {
                        val now = System.currentTimeMillis()
                        store.markModeHandoffReady(
                            packageName,
                            PushCompatContract.DELIVERY_MODE_ON_DEVICE,
                            now,
                        )
                        val readyAt =
                            store.modeHandoffReadyAt(
                                packageName,
                                PushCompatContract.DELIVERY_MODE_ON_DEVICE,
                            ) ?: now
                        val remaining = TOKEN_HANDOFF_GRACE_MILLIS - (now - readyAt)
                        if (remaining > 0L) {
                            reconcileAfter(remaining)
                        } else {
                            try {
                                bridgeClient.unregister(packageName)
                                if (!bridgeClient.isCurrent()) {
                                    return failedReconciliation()
                                }
                                remotePackages.remove(packageName)
                                store.clearModeHandoff(packageName)
                            } catch (e: Exception) {
                                retryNeeded = true
                                Slog.w(
                                    TAG,
                                    "Failed to remove bridge registration for $packageName",
                                    e,
                                )
                            }
                        }
                    } else if (packageName !in remotePackages) {
                        store.clearModeHandoff(packageName)
                    }
                }

                else -> {
                    error("unknown app transport mode")
                }
            }
        }
        // A pass that skipped every register call because none was due must
        // leave the clock alone, or the bridge's staleness reaper never sees
        // a heartbeat.
        if (bridgeRegistrationSucceeded && !bridgeRegistrationFailed) {
            store.setLastRegistrationMillis(bridgeUrl, System.currentTimeMillis())
            if (reconnectRequested) {
                store.setLastRegistrationMillis(bridgeUrl, 0L)
            }
        }
        if (!bridgeClient.isCurrent()) {
            return failedReconciliation()
        }
        store.setRemotelyRegisteredPackages(bridgeUrl, remotePackages)
        val bridgePackages =
            desiredReadyPackages.filterTo(mutableSetOf()) { packageName ->
                packageName in remotePackages &&
                    !store.fcmTokenFor(bridgeUrl, packageName).isNullOrEmpty()
            }
        val onDeviceIdentities =
            store
                .onDeviceRegistrations()
                .filter { it.appId in desiredReadyPackages }
                .map { it.identity(userId) }
        val activePackages =
            bridgePackages
                .plus(onDeviceIdentities.map { it.appId })
                .toSet()
        return UserReconciliation(
            userId = userId,
            store = store,
            activePackages = activePackages,
            bridgeWork =
                bridgePackages.isNotEmpty() || store.nativeRegistrations().isNotEmpty(),
            onDeviceIdentities = onDeviceIdentities,
            retryNeeded = retryNeeded,
            nextReconcileMillis = nextReconcileMillis,
        )
    }

    private fun reconcileNativeRegistrations(
        userId: Int,
        store: RelayStore,
        bridgeClient: BridgeClient,
    ): Boolean {
        val snapshot = store.nativeRegistrationSnapshot()
        if (snapshot.malformedCount > 0) {
            Slog.e(
                TAG,
                "Skipping UnifiedPush reconcile for user $userId because " +
                    "${snapshot.malformedCount} stored registrations are malformed",
            )
            return true
        }
        val registrations = snapshot.registrations
        var retryNeeded = false
        try {
            val endpoints = bridgeClient.reconcileUnifiedPush(registrations)
            if (!bridgeClient.isCurrent()) {
                return true
            }
            for (registration in registrations) {
                val endpoint = endpoints[registration.connectorToken]
                if (endpoint == null) {
                    retryNeeded = true
                    Slog.e(
                        TAG,
                        "Bridge omitted UnifiedPush registration for ${registration.appId}",
                    )
                    continue
                }
                if (endpoint != registration.endpoint) {
                    val updated = registration.copy(endpoint = endpoint)
                    UnifiedPushDistributor.notifyNewEndpoint(this, userId, updated)
                    store.saveNativeRegistration(updated)
                }
            }
        } catch (e: Exception) {
            retryNeeded = true
            Slog.e(
                TAG,
                "Failed to reconcile UnifiedPush registrations",
                e,
            )
        }
        return retryNeeded
    }

    private fun deliverSocketMessage(
        reconciliation: UserReconciliation,
        message: SocketEnvelope,
    ): Boolean {
        val userId = reconciliation.userId
        val delivered =
            try {
                when (message.kind) {
                    "fcm" -> {
                        if (message.appId !in reconciliation.activePackages) {
                            false
                        } else {
                            AppDispatcher.deliverMessage(
                                this,
                                userId,
                                message.appId,
                                message.payload,
                            )
                        }
                    }

                    "unified_push" -> {
                        val connectorToken = message.connectorToken
                        connectorToken != null &&
                            UnifiedPushDistributor.deliverMessage(
                                this,
                                userId,
                                message.appId,
                                connectorToken,
                                message.payload,
                            )
                    }

                    else -> {
                        false
                    }
                }
            } catch (e: Exception) {
                Slog.e(TAG, "Dropping ${message.kind} message for ${message.appId}", e)
                false
            }
        reconciliation.store.addDeliveryEntry(
            DeliveryEntry(
                timestamp = System.currentTimeMillis(),
                appId = message.appId,
                kind = message.kind,
                delivered = delivered,
                detail = if (delivered) "Delivered" else "Not delivered",
            ),
        )
        return delivered
    }

    private fun requestRelay(resetBackoff: Boolean = false) {
        val transportToClose =
            synchronized(wakeMonitor) {
                reconnectRequested = true
                if (resetBackoff) {
                    networkReconnectRequested = true
                }
                wakeMonitor.notifyAll()
                if (!workerRunning) {
                    workerRunning = true
                    executor.execute { runRelay() }
                }
                activeTransport
            }
        transportToClose?.close()
    }

    private fun requestRefresh() {
        synchronized(wakeMonitor) {
            refreshRequested = true
            wakeMonitor.notifyAll()
        }
    }

    private fun executeNetworkChange(action: () -> Unit) {
        try {
            retryScheduler.execute {
                if (!destroyed) {
                    action()
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun waitForReconcile(delayMillis: Long) {
        synchronized(wakeMonitor) {
            if (!destroyed && !reconnectRequested && !refreshRequested) {
                wakeMonitor.wait(delayMillis)
            }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL_ID,
                getString(R.string.relay_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            },
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, relayNotification(text))
    }

    private fun relayNotification(text: String): Notification {
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent("com.android.settings.PUSHCOMPAT_SETTINGS")
                    .setPackage("com.android.settings"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification
            .Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pushcompat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateSocketState(
        @SocketState state: Int,
    ) {
        var stableConnectionStartedAt = 0L
        val changed =
            synchronized(SOCKET_HEALTH_LOCK) {
                if (currentSocketState == state) {
                    false
                } else {
                    val now = System.currentTimeMillis()
                    currentSocketState = state
                    socketStateSinceMillis = now
                    if (state == PushCompatContract.SOCKET_CONNECTED) {
                        connectedSinceMillis = now
                        stableConnectionStartedAt = now
                    } else {
                        connectedSinceMillis = 0L
                    }
                    true
                }
            }
        if (changed) {
            ManagementProvider.notifyStatus(this)
        }
        if (stableConnectionStartedAt != 0L) {
            retryScheduler.schedule(
                { markSocketStable(stableConnectionStartedAt) },
                STABLE_CONNECTION_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun markSocketStable(connectionStartedAt: Long) {
        val stable =
            synchronized(SOCKET_HEALTH_LOCK) {
                if (currentSocketState != PushCompatContract.SOCKET_CONNECTED ||
                    connectedSinceMillis != connectionStartedAt
                ) {
                    false
                } else {
                    consecutiveConnectFailures = 0
                    lastSocketError = null
                    true
                }
            }
        if (stable) {
            ManagementProvider.notifyStatus(this)
        }
    }

    private fun recordSocketFailure(error: Exception?) {
        if (networkChangedRecently()) {
            return
        }
        synchronized(SOCKET_HEALTH_LOCK) {
            val now = System.currentTimeMillis()
            if (currentSocketState == PushCompatContract.SOCKET_CONNECTED &&
                connectedSinceMillis > 0L &&
                now - connectedSinceMillis >= STABLE_CONNECTION_MILLIS
            ) {
                consecutiveConnectFailures = 0
            }
            consecutiveConnectFailures++
            connectedSinceMillis = 0L
            lastSocketError =
                (error?.message ?: error?.javaClass?.simpleName ?: "Connection closed")
                    .take(MAX_SOCKET_ERROR_LENGTH)
        }
        ManagementProvider.notifyStatus(this)
    }

    private fun clearSocketFailuresAfterNetworkChange() {
        val changed =
            synchronized(SOCKET_HEALTH_LOCK) {
                if (consecutiveConnectFailures == 0 && lastSocketError == null) {
                    false
                } else {
                    consecutiveConnectFailures = 0
                    lastSocketError = null
                    true
                }
            }
        if (changed) {
            ManagementProvider.notifyStatus(this)
        }
    }

    private fun networkChangedRecently(): Boolean =
        synchronized(networkStateLock) {
            lastNetworkChangeElapsedMillis > 0L &&
                SystemClock.elapsedRealtime() - lastNetworkChangeElapsedMillis <=
                NETWORK_CHANGE_FAILURE_WINDOW_MILLIS
        }

    companion object {
        private const val TAG = "PushCompat"
        private const val SERVICE_CHANNEL_ID = "pushcompat_relay_status"
        private const val NOTIFICATION_ID = 0x46434d
        private const val INITIAL_RECONNECT_MILLIS = 1_000L
        private const val MAX_RECONNECT_MILLIS = 5L * 60L * 1000L
        private const val STABLE_CONNECTION_MILLIS = 60_000L
        private const val NETWORK_CHANGE_FAILURE_WINDOW_MILLIS = 5_000L
        private const val TOKEN_HANDOFF_GRACE_MILLIS = 30_000L
        private const val MAX_SOCKET_ERROR_LENGTH = 256

        private val SOCKET_HEALTH_LOCK = Any()

        @SocketState
        private var currentSocketState = PushCompatContract.SOCKET_IDLE
        private var socketStateSinceMillis = System.currentTimeMillis()
        private var connectedSinceMillis = 0L
        private var consecutiveConnectFailures = 0
        private var lastSocketError: String? = null

        fun socketHealth(): PushSocketHealth =
            synchronized(SOCKET_HEALTH_LOCK) {
                PushSocketHealth(
                    state = currentSocketState,
                    stateSinceMillis = socketStateSinceMillis,
                    connectedSinceMillis = connectedSinceMillis,
                    consecutiveConnectFailures = consecutiveConnectFailures,
                    lastError = lastSocketError,
                )
            }

        fun reconcile(context: Context) {
            context.startForegroundService(Intent(context, PushService::class.java))
        }
    }

    private data class UserReconciliation(
        val userId: Int,
        val store: RelayStore,
        val activePackages: Set<String>,
        val bridgeWork: Boolean,
        val onDeviceIdentities: List<OnDeviceMcsIdentity>,
        val retryNeeded: Boolean,
        val nextReconcileMillis: Long?,
    )

    private class CloseableGroup(
        private val transports: List<Closeable>,
    ) : Closeable {
        private var closed = false

        override fun close() {
            val current =
                synchronized(this) {
                    if (closed) {
                        emptyList()
                    } else {
                        closed = true
                        transports
                    }
                }
            current.forEach { transport ->
                runCatching { transport.close() }
            }
        }
    }
}
