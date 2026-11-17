package com.cherret.zaprett.byedpi

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cherret.zaprett.MainActivity
import com.cherret.zaprett.R
import com.cherret.zaprett.TvActivity
import com.cherret.zaprett.data.ListType
import com.cherret.zaprett.data.ServiceStatus
import com.cherret.zaprett.utils.disableList
import com.cherret.zaprett.utils.getActiveByeDPIStrategyContent
import com.cherret.zaprett.utils.getActiveExcludeIpsets
import com.cherret.zaprett.utils.getActiveExcludeLists
import com.cherret.zaprett.utils.getActiveIpsets
import com.cherret.zaprett.utils.getActiveLists
import com.cherret.zaprett.utils.getAppsListMode
import com.cherret.zaprett.utils.getHostListMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ByeDpiVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyJob: Job? = null
    private lateinit var sharedPreferences: SharedPreferences
    companion object {
        private const val CHANNEL_ID = "zaprett_vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "START_VPN"
        private const val ACTION_STOP = "STOP_VPN"
        private val mutableStatus = MutableStateFlow(ServiceStatus.Disconnected)
        val statusFlow = mutableStatus.asStateFlow()
        val status: ServiceStatus get() = statusFlow.value

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ByeDpiVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ByeDpiVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return when (intent?.action) {
            ACTION_START -> {
                if (vpnInterface == null) setupProxy()
                START_STICKY
            }
            ACTION_STOP -> {
                stopProxy()
                mutableStatus.value = ServiceStatus.Disconnected
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopProxy()
        if (status != ServiceStatus.Failed) mutableStatus.value = ServiceStatus.Disconnected
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_zaprett_proxy),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_zaprett_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val activity = if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            TvActivity::class.java
        } else {
            MainActivity::class.java
        }
        val notificationIntent = Intent(this, activity)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_zaprett_proxy))
            .setContentText(getString(R.string.notification_zaprett_description))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.btn_stop_service),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, ByeDpiVpnService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .build()
    }

    private fun setupProxy() {
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            startSocksProxy()
            startByeDpi()
            mutableStatus.value = ServiceStatus.Connected
        } catch (e: Exception) {
            failProxy(e)
        } catch (e: LinkageError) {
            failProxy(e)
        }
    }

    private fun failProxy(error: Throwable) {
        Log.e("proxy", "Failed to start or keep the proxy running", error)
        mutableStatus.value = ServiceStatus.Failed
        stopSelf()
    }

    private fun startSocksProxy() {
        val dns = sharedPreferences.getString("dns", "8.8.8.8")?: "8.8.8.8"
        val ipv6 = sharedPreferences.getBoolean("ipv6", false)
        val socksIp = sharedPreferences.getString("ip", "127.0.0.1")?: "127.0.0.1"
        val socksPort = sharedPreferences.getString("port", "1080")?: "1080"
        val builder = Builder()
        builder.setSession(getString(R.string.notification_zaprett_proxy))
            .setMtu(1500)
            .addAddress("10.10.10.10", 32)
            .addDnsServer(dns)
            .addRoute("0.0.0.0", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        if (ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }
        val appList = getAppsListMode(sharedPreferences)
        when (appList) {
            "blacklist" -> {
                builder.addDisallowedApplication(applicationContext.packageName)
                val blacklist = sharedPreferences.getStringSet("blacklist", emptySet())?: emptySet()
                blacklist.forEach {
                    builder.addDisallowedApplication(it)
                }
            }
            "whitelist" -> {
                val whitelist = sharedPreferences.getStringSet("whitelist", emptySet())?: emptySet()
                whitelist.forEach {
                    builder.addAllowedApplication(it)
                }
            }
            else -> {
                builder.addDisallowedApplication(applicationContext.packageName)
            }
        }
        Log.d("builder", builder.toString())
        val tunnel = checkNotNull(builder.establish()) { "VPN permission was revoked" }
        vpnInterface = tunnel
        val tun2socksConfig = """
        | misc:
        |   task-stack-size: 81920
        | socks5:
        |   mtu: 8500
        |   address: $socksIp
        |   port: $socksPort
        |   udp: udp
        """.trimMargin("| ")
        val configPath = File.createTempFile("config", "tmp", cacheDir).apply {
            writeText(tun2socksConfig)
            deleteOnExit()
        }
        check(TProxyService.TProxyStartService(configPath.absolutePath, tunnel.fd)) {
            "Couldn't start the tunnel"
        }
    }

    private fun stopProxy() {
        proxyJob?.cancel()
        val tunnel = vpnInterface ?: return
        vpnInterface = null
        runCatching { NativeBridge().jniStopProxy() }
            .onFailure { Log.e("proxy", "Couldn't stop ByeDPI", it) }
        runCatching { TProxyService.TProxyStopService() }
            .onFailure { Log.e("proxy", "Couldn't stop the tunnel", it) }
        runCatching { tunnel.close() }
            .onFailure { Log.e("proxy", "Couldn't close the VPN interface", it) }
    }

    private fun startByeDpi() {
        val socksIp = sharedPreferences.getString("ip", "127.0.0.1")?: "127.0.0.1"
        val socksPort = sharedPreferences.getString("port", "1080")?: "1080"
        val listSet = if (getHostListMode(sharedPreferences) == ListType.whitelist) getActiveLists(sharedPreferences) else getActiveExcludeLists(sharedPreferences)
        val ipsetSet = if (getHostListMode(sharedPreferences) == ListType.whitelist) getActiveIpsets(sharedPreferences) else getActiveExcludeIpsets(sharedPreferences)
        proxyJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val args = parseArgs(
                        socksIp,
                        socksPort,
                        getActiveByeDPIStrategyContent(sharedPreferences),
                        prepareList(listSet.map { it.file }.toTypedArray()),
                        prepareIpset(ipsetSet.map { it.file }.toTypedArray()),
                        sharedPreferences
                    )
                    ensureActive()
                    NativeBridge().jniStartProxy(args)
                }
                failProxy(IllegalStateException("ByeDPI exited with status $result"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failProxy(e)
            } catch (e: LinkageError) {
                failProxy(e)
            }
        }
    }
    private suspend fun prepareList(actlists: Array<String>): String {
        if (actlists.isNotEmpty()) {
            val lists: Array<File> = actlists.map { File(it) }.toTypedArray()
            val hostlist = withContext(Dispatchers.IO) {
                File.createTempFile("hostlist", ".txt", cacheDir)
            }.apply { deleteOnExit() }
            withContext(Dispatchers.IO) {
                hostlist.printWriter().use { out ->
                    lists.forEach {
                        if (it.exists()) {
                            it.bufferedReader().useLines {
                                it.forEach {
                                    out.println(it)
                                }
                            }
                        } else {
                            disableList(it.name, sharedPreferences)
                        }
                    }
                }
            }
            return hostlist.absolutePath
        }
        return ""
    }

    private suspend fun prepareIpset(actsets: Array<String>): String {
        if (actsets.isNotEmpty()) {
            val lists: Array<File> = actsets.map { File(it) }.toTypedArray()
            val hostlist = withContext(Dispatchers.IO) {
                File.createTempFile("ipset", ".txt", cacheDir)
            }.apply { deleteOnExit() }
            withContext(Dispatchers.IO) {
                hostlist.printWriter().use { out ->
                    lists.forEach {
                        if (it.exists()) {
                            it.bufferedReader().useLines {
                                it.forEach {
                                    out.println(it)
                                }
                            }
                        } else {
                            disableList(it.name, sharedPreferences)
                        }
                    }
                }
            }
            return hostlist.absolutePath
        }
        return ""
    }

    private fun parseArgs(ip: String, port: String, rawArgs: List<String>, list : String, ipset : String, sharedPreferences: SharedPreferences): Array<String> {
        val regex = Regex("""--?\S+(?:=(?:[^"'\s]+|"[^"]*"|'[^']*'))?|[^\s]+""")
        val parsedArgs = rawArgs
            .flatMap { args -> regex.findAll(args).map { it.value } }
            .flatMap { arg ->
                if (getHostListMode(sharedPreferences) == ListType.whitelist) {
                    when {
                        arg == "\$hostlist" && list.isNotEmpty() -> listOf("--hosts", list)
                        arg == "\$hostlist" && list.isEmpty() -> emptyList()
                        arg == "\$ipset" && list.isNotEmpty() -> listOf("--ipset", list)
                        arg == "\$ipset" && list.isEmpty() -> emptyList()
                        else -> listOf(arg)
                    }
                } else {
                    when {
                        arg == "\$hostlist" && list.isNotEmpty() -> listOf("--hosts", list, "-An", list)
                        arg == "\$ipset" && ipset.isNotEmpty() -> listOf("--ipset", ipset, "-An", ipset)
                        arg == "\$hostlist" || arg == "\$ipset" -> emptyList()
                        else -> listOf(arg)
                    }
                }
            }
            .toMutableList()
        return arrayOf("ciadpi", "--ip", ip, "--port", port) + parsedArgs
    }
}
