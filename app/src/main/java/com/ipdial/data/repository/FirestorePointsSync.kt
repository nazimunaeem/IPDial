package com.ipdial.data.repository

// Point system dropped — FirestorePointsSync stubbed out.
class FirestorePointsSync(private val repo: AccountRepository) {
    fun startListening() {}
    fun stopListening() {}
    fun incrementPoints(amount: Int) {}
}
