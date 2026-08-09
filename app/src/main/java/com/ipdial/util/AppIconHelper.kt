package com.ipdial.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object AppIconHelper {
    private const val MAIN_ACTIVITY = "com.ipdial.MainActivity"
    private const val DEFAULT_ALIAS = "com.ipdial.MainActivityDefault"
    
    private val ALIASES = mapOf(
        "Default" to DEFAULT_ALIAS,
        "Green"   to "com.ipdial.MainActivityGreen",
        "Blue"    to "com.ipdial.MainActivityBlue",
        "Red"     to "com.ipdial.MainActivityRed"
    )

    fun setAppIcon(context: Context, aliasName: String) {
        val pm = context.packageManager
        val packageName = context.packageName
        
        val targetAlias = ALIASES[aliasName] ?: DEFAULT_ALIAS
        android.util.Log.d("AppIconHelper", "Setting icon to: $aliasName ($targetAlias)")

        // 1. First, ensure the target alias is enabled
        val targetComp = ComponentName(packageName, targetAlias)
        if (pm.getComponentEnabledSetting(targetComp) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            pm.setComponentEnabledSetting(
                targetComp,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }

        // 2. Disable all other aliases
        ALIASES.values.forEach { alias ->
            if (alias != targetAlias) {
                val comp = ComponentName(packageName, alias)
                if (pm.getComponentEnabledSetting(comp) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(
                        comp,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }

        // 3. FORCE ensure MainActivity is ENABLED. 
        // This is the stable entry point for Android Studio.
        val mainComp = ComponentName(packageName, MAIN_ACTIVITY)
        if (pm.getComponentEnabledSetting(mainComp) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            pm.setComponentEnabledSetting(
                mainComp,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    /**
     * Emergency recovery to ensure the app is launchable by the IDE.
     */
    fun forceEnableMainActivity(context: Context) {
        try {
            val pm = context.packageManager
            val packageName = context.packageName
            val mainComp = ComponentName(packageName, MAIN_ACTIVITY)
            
            // Only set if not already explicitly enabled. 
            // Note: COMPONENT_ENABLED_STATE_DEFAULT (0) means it follows the manifest, 
            // which is 'enabled=true', so we don't necessarily NEED to set it to 1 
            // if it's already 0. Setting it to 1 from 0 can trigger a process restart.
            val state = pm.getComponentEnabledSetting(mainComp)
            android.util.Log.d("AppIconHelper", "MainActivity state check: current=$state")

            if (state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED && 
                state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                pm.setComponentEnabledSetting(
                    mainComp,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                android.util.Log.d("AppIconHelper", "Forced MainActivity to ENABLED")
            }
        } catch (e: Exception) {
            android.util.Log.e("AppIconHelper", "Emergency recovery failed", e)
        }
    }
}
