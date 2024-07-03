// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import android.util.Log
import libtailscale.Libtailscale
import java.util.UUID

open class IPNService : VpnService(), libtailscale.IPNService {
  private val TAG = "IPNService"
  private val randomID: String = UUID.randomUUID().toString()

  override fun id(): String {
    return randomID
  }

  override fun onCreate() {
    super.onCreate()
    // grab app to make sure it initializes
    App.get()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
      when (intent?.action) {
        ACTION_STOP_VPN -> {
          App.get().setWantRunning(false)
          close()
          START_NOT_STICKY
        }
        ACTION_START_VPN -> {
          showForegroundNotification()
          App.get().setWantRunning(true)
          Libtailscale.requestVPN(this)
          START_STICKY
        }
        "android.net.VpnService" -> {
          // This means we were started by Android due to Always On VPN.
          // We show a non-foreground notification because we weren't
          // started as a foreground service.
          App.get().notifyStatus(true)
          App.get().setWantRunning(true)
          Libtailscale.requestVPN(this)
          START_STICKY
        }
        else -> {
          // This means that we were restarted after the service was killed
          // (potentially due to OOM).
          if (UninitializedApp.get().isAbleToStartVPN()) {
            showForegroundNotification()
            App.get()
            Libtailscale.requestVPN(this)
            START_STICKY
          } else {
            START_NOT_STICKY
          }
        }
      }

  override fun close() {
    stopForeground(STOP_FOREGROUND_REMOVE)
    Libtailscale.serviceDisconnect(this)
  }

  override fun onDestroy() {
    close()
    super.onDestroy()
  }

  override fun onRevoke() {
    close()
    super.onRevoke()
  }

  private fun showForegroundNotification() {
    try {
      startForeground(
          UninitializedApp.STATUS_NOTIFICATION_ID,
          UninitializedApp.get().buildStatusNotification(true))
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start foreground service: $e")
    }
  }

  private fun configIntent(): PendingIntent {
    return PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun disallowApp(b: Builder, name: String) {
    try {
      b.addDisallowedApplication(name)
    } catch (e: PackageManager.NameNotFoundException) {
      Log.d(TAG, "Failed to add disallowed application: $e")
    }
  }

  override fun newBuilder(): VPNServiceBuilder {
    val b: Builder =
        Builder()
            .setConfigureIntent(configIntent())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      b.setMetered(false) // Inherit the metered status from the underlying networks.
    }
    b.setUnderlyingNetworks(null) // Use all available networks.

    // Prevent certain apps from getting their traffic + DNS routed via Tailscale:
    // - any app that the user manually disallowed in the GUI
    // - any app that we disallowed via hard-coding
    for (disallowedPackageName in UninitializedApp.get().disallowedPackageNames()) {
      Log.d(TAG, "Disallowing app: $disallowedPackageName")
      disallowApp(b, disallowedPackageName)
    }

    return VPNServiceBuilder(b)
  }

  companion object {
    const val ACTION_START_VPN = "com.tailscale.ipn.START_VPN"
    const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
  }
}
