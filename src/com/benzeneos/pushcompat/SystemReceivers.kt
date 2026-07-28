package com.benzeneos.pushcompat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // BOOT_COMPLETED can arrive per-user before singleton permissions are loaded.
        if (Process.myUserHandle().identifier != UserHandle.USER_SYSTEM) {
            return
        }
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            existingProfileIds(context).forEach { userId ->
                val store = RelayStore(context, userId)
                store.enabledPackages().forEach { packageName ->
                    if (!isPackageInstalled(context, userId, packageName)) {
                        store.setPackageEnabled(packageName, false)
                    }
                }
            }
            PushService.reconcile(context)
        }
    }

    private fun isPackageInstalled(
        context: Context,
        userId: Int,
        packageName: String,
    ): Boolean =
        try {
            context.asUser(userId).packageManager.getPackageInfo(
                packageName,
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val reconcile =
            sendingUser.identifier.takeIf { it >= 0 }?.let { userId ->
                updateStore(RelayStore(context, userId), intent)
            } == true
        ManagementProvider.notifyStatus(context)
        ManagementProvider.notifyApps(context)
        if (reconcile) {
            PushService.reconcile(context)
        }
    }

    private fun updateStore(
        store: RelayStore,
        intent: Intent,
    ): Boolean {
        val packageName = intent.data?.schemeSpecificPart ?: return false
        val relevant =
            packageName in store.enabledPackages() ||
                packageName in store.frameworkEnabledPackages() ||
                store.nativeRegistrations().any { it.appId == packageName }
        if (intent.action == Intent.ACTION_PACKAGE_FULLY_REMOVED) {
            store.setPackageEnabled(packageName, false)
        }
        if (!relevant) {
            return false
        }
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED,
            -> store.markRegistrationDue()
        }
        return true
    }
}

class ProfileChangeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        ManagementProvider.notifyStatus(context)
        ManagementProvider.notifyApps(context)
        val userId =
            intent
                .getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
                ?.identifier
                ?: intent
                    .getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL)
                    .takeIf { it != UserHandle.USER_NULL }
                ?: return
        val store = RelayStore(context, userId)
        if (store.enabledPackages().isNotEmpty() ||
            store.frameworkEnabledPackages().isNotEmpty() ||
            store.nativeRegistrations().isNotEmpty() ||
            store.remotelyRegisteredPackagesFor(store.bridgeUrl).isNotEmpty()
        ) {
            PushService.reconcile(context)
        }
    }
}
