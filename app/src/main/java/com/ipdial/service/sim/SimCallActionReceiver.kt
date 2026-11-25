package com.ipdial.service.sim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ipdial.MainActivity
import com.ipdial.service.cancelIncomingNotification

class SimCallActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SimCallActionReceiver"
        const val ACTION_SIM_ANSWER = "com.ipdial.SIM_ANSWER"
        const val ACTION_SIM_DECLINE = "com.ipdial.SIM_DECLINE"
        const val ACTION_SIM_HANGUP = "com.ipdial.SIM_HANGUP"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "onReceive: action=$action")
        when (action) {
            ACTION_SIM_ANSWER -> {
                SimCallManager.answerCall()
                cancelIncomingNotification(context)
                val showIntent = Intent(context, MainActivity::class.java).apply {
                    this.action = "com.ipdial.ACTION_SHOW_CALL"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                try {
                    context.startActivity(showIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch MainActivity after SIM answer: ${e.message}")
                }
            }
            ACTION_SIM_DECLINE,
            ACTION_SIM_HANGUP -> {
                SimCallManager.hangup()
                cancelIncomingNotification(context)
            }
        }
    }
}
