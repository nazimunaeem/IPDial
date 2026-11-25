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
    val handle: PhoneAccountHandle,
    val subscriptionId: Int? = null
)

/**
 * Result of a USSD/MMI request so the UI can show the network response.
 */
data class UssdSession(
    val request: String,
    val response: String? = null,
    val error: String? = null
)
