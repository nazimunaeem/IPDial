package com.ipdial.data.repository

import android.content.Context

// OAuth dropped — AuthRepository stubbed out.
class AuthRepository(private val context: Context) {
    val isSignedIn: Boolean get() = false
    val userId: String? get() = null
    val referralCode: String get() = ""
}
