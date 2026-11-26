package com.ipdial.service

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallPresentationHelperTest {

    @Test
    fun isCallLaunchAction_recognizesIncomingAndShowCallActionsOnly() {
        assertTrue(IncomingCallPresentationHelper.isCallLaunchAction(IncomingCallPresentationHelper.ACTION_INCOMING_CALL))
        assertTrue(IncomingCallPresentationHelper.isCallLaunchAction(IncomingCallPresentationHelper.ACTION_SHOW_CALL))
        assertFalse(IncomingCallPresentationHelper.isCallLaunchAction(Intent.ACTION_MAIN))
        assertFalse(IncomingCallPresentationHelper.isCallLaunchAction(null))
        assertFalse(IncomingCallPresentationHelper.isCallLaunchAction("com.ipdial.OTHER"))
    }

    @Test
    fun shouldPresentIncomingOverLockScreen_falseOnlyWhenForegroundUnlockedAndInteractive() {
        assertFalse(
            IncomingCallPresentationHelper.shouldPresentIncomingOverLockScreen(
                isAppForeground = true,
                isKeyguardLocked = false,
                isScreenInteractive = true
            )
        )
    }

    @Test
    fun shouldPresentIncomingOverLockScreen_trueWhenBackgroundOrLockedOrScreenOff() {
        // Backgrounded while unlocked and screen on (shows heads-up CallStyle notification)
        assertTrue(
            IncomingCallPresentationHelper.shouldPresentIncomingOverLockScreen(
                isAppForeground = false,
                isKeyguardLocked = false,
                isScreenInteractive = true
            )
        )
        // App reported foreground during lock transition while keyguard is locked
        assertTrue(
            IncomingCallPresentationHelper.shouldPresentIncomingOverLockScreen(
                isAppForeground = true,
                isKeyguardLocked = true,
                isScreenInteractive = true
            )
        )
        // App reported foreground while screen turned off
        assertTrue(
            IncomingCallPresentationHelper.shouldPresentIncomingOverLockScreen(
                isAppForeground = true,
                isKeyguardLocked = false,
                isScreenInteractive = false
            )
        )
        // Standard locked + screen off + backgrounded
        assertTrue(
            IncomingCallPresentationHelper.shouldPresentIncomingOverLockScreen(
                isAppForeground = false,
                isKeyguardLocked = true,
                isScreenInteractive = false
            )
        )
    }

    @Test
    fun shouldDirectlyStartIncomingActivity_trueWhenLockedOrScreenOff() {
        assertFalse(
            IncomingCallPresentationHelper.shouldDirectlyStartIncomingActivity(
                isKeyguardLocked = false,
                isScreenInteractive = true
            )
        )
        assertTrue(
            IncomingCallPresentationHelper.shouldDirectlyStartIncomingActivity(
                isKeyguardLocked = true,
                isScreenInteractive = true
            )
        )
        assertTrue(
            IncomingCallPresentationHelper.shouldDirectlyStartIncomingActivity(
                isKeyguardLocked = false,
                isScreenInteractive = false
            )
        )
        assertTrue(
            IncomingCallPresentationHelper.shouldDirectlyStartIncomingActivity(
                isKeyguardLocked = true,
                isScreenInteractive = false
            )
        )
    }
}
