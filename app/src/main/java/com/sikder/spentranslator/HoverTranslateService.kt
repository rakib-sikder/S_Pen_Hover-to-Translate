// app/src/main/java/com/sikder/spentranslator/services/HoverTranslateService.kt
// Make sure your package name matches your project structure.
package com.sikder.spentranslator.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class HoverTranslateService : Service() {

    private val TAG = "HoverTranslateService"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service Created")
        // TODO: Initialize S Pen SDK or other components here if needed when service is first created
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Service Started")
        // TODO: Start your S Pen hover detection logic here
        // TODO: Or, if it's a long-running task, consider starting a foreground service
        //       to prevent the system from killing it. For now, this is fine for testing.

        // If the service is killed, Btry to recreate it.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called, returning null as we are not binding.")
        // We are not binding to this service from other components for now, so return null.
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Service Stopped and Destroyed")
        // TODO: Clean up resources here (e.g., unregister S Pen listeners)
    }
}