package com.m4isper.tictactoe.api

/** Bounds live sockets, both in total and per address; games alone do not bound connections. */
class ConnectionLimiter(
    private val maxPerIp: Int,
    private val maxTotal: Int,
) {
    private val lock = Any()
    private val perIp = mutableMapOf<String, Int>()
    private var total = 0

    fun acquire(ip: String): Boolean = synchronized(lock) {
        if (total >= maxTotal || (perIp[ip] ?: 0) >= maxPerIp) return false
        total++
        perIp[ip] = (perIp[ip] ?: 0) + 1
        true
    }

    fun release(ip: String): Unit = synchronized(lock) {
        if (total == 0) return
        total--
        val left = (perIp[ip] ?: 0) - 1
        if (left <= 0) perIp.remove(ip) else perIp[ip] = left
    }

    fun total(): Int = synchronized(lock) { total }

    fun count(ip: String): Int = synchronized(lock) { perIp[ip] ?: 0 }
}
