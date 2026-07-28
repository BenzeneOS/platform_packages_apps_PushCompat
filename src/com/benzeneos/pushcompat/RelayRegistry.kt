package com.benzeneos.pushcompat

import android.content.Context
import android.content.pm.GosPackageState
import android.content.pm.GosPackageStateFlag
import android.content.pm.PackageManager
import android.util.Slog

data class EnabledFirebaseTargets(
    val availableTargets: List<FirebaseTarget>,
    val ready: Map<String, FirebaseTarget>,
    val unavailableTargets: Map<String, FirebaseTarget?>,
) {
    val unavailablePackages: Set<String>
        get() = unavailableTargets.keys
}

object RelayRegistry {
    private const val TAG = "PushCompat"
    private const val GMS_PACKAGE = "com.google.android.gms"

    fun isGmsPresent(
        context: Context,
        userId: Int,
    ): Boolean =
        try {
            context.asUser(userId).packageManager.getPackageInfo(
                GMS_PACKAGE,
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    fun enabledFirebaseTargets(
        context: Context,
        store: RelayStore,
        targets: List<FirebaseTarget> = FirebaseDiscovery.discover(context, store.userId),
    ): EnabledFirebaseTargets {
        val enabledPackages = enabledFirebasePackages(store, isGmsPresent(context, store.userId))
        val availableTargets = targets.filter { it.isReady && it.isAppEnabled }
        val ready =
            availableTargets
                .filter { it.packageName in enabledPackages }
                .associateBy { it.packageName }
        val targetsByPackage = targets.associateBy { it.packageName }
        return EnabledFirebaseTargets(
            availableTargets = availableTargets,
            ready = ready,
            unavailableTargets =
                (enabledPackages - ready.keys).associateWith(targetsByPackage::get),
        )
    }

    private fun enabledFirebasePackages(
        store: RelayStore,
        gmsPresent: Boolean,
    ): Set<String> =
        if (gmsPresent) {
            emptySet()
        } else {
            store.enabledPackages() -
                store.nativeRegistrations().mapTo(mutableSetOf()) { it.appId }
        }

    fun syncFrameworkFlags(
        store: RelayStore,
        desiredPackages: Set<String>,
    ) {
        val previousPackages = store.frameworkEnabledPackages()
        val appliedPackages = previousPackages.toMutableSet()
        for (packageName in previousPackages + desiredPackages) {
            val enabled = packageName in desiredPackages
            val applied =
                runCatching {
                    GosPackageState
                        .edit(packageName, store.userId)
                        .setFlagState(GosPackageStateFlag.PUSH_COMPAT_RELAY, enabled)
                        .apply()
                }.onFailure { error ->
                    Slog.e(
                        TAG,
                        "Failed to sync PUSH_COMPAT_RELAY for $packageName u${store.userId}",
                        error,
                    )
                }.getOrDefault(false)
            if (enabled && applied) {
                appliedPackages.add(packageName)
            } else if (!enabled || !applied) {
                appliedPackages.remove(packageName)
            }
        }
        store.setFrameworkEnabledPackages(appliedPackages)
    }
}
