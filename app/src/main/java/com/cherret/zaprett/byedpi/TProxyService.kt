package com.cherret.zaprett.byedpi

object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    external fun TProxyStartService(config: String, fd: Int): Boolean
    external fun TProxyGetStats(): LongArray
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
}
