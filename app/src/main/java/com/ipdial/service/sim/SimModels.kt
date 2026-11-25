package com.ipdial.service.sim

import android.telecom.PhoneAccountHandle

/**
 * Represents a selectable cellular SIM line discovered via TelecomManager / SubscriptionManager.
 */
data class SimLine(
    val id: String,
    val label: String,
    val subtitle: String,
    val slotIndex: Int?,
    val handle: PhoneAccountHandle
)
