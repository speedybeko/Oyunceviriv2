package com.berkay.oyunceviri

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.berkay.oyunceviri.core.SubtitleSink
import com.berkay.oyunceviri.core.TvProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Telefon -> Android TV bağlantısı.
 *  - NSD ile TV'yi bulur (IP girmek gerekmez),
 *  - WebSocket'e `?token=` ile bağlanır; TV doğru kodu görünce "hello" yollar,
 *  - koparsa artan beklemeyle (2 sn -> 15 sn) yeniden dener; kod yanlışsa (1008) dener durur,
 *  - [state] ile durumunu arayüze bildirir, [SubtitleSink] olarak altyazıyı TV'ye iletir.
 *
 * ws:// düz metindir; yerel ağ + eşleştirme kodu kişisel kullanım için yeterli kabul edildi.
 */
class TvLink(
    context: Context,
    private val scope: CoroutineScope,
    token: String,
    private val styleMessage: () -> String
) : SubtitleSink {

    sealed interface State {
        object Idle : State
        object Searching : State
        data class Connecting(val host: String) : State
        data class Connected(val host: String) : State
        object Unauthorized : State
        object Lost : State
    }

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val token = token.trim()
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var running = false
    @Volatile private var socket: WebSocket? = null
    @Volatile private var authorized = false
    private val resolving = AtomicBoolean(false)
    private var discovery: NsdManager.DiscoveryListener? = null
    private var reconnectJob: Job? = null
    private var lastHost: String? = null
    private var lastPort = 0
    private var failures = 0

    fun start() {
        if (running) return
        running = true
        failures = 0
        setState(State.Searching)
        startDiscovery()
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        stopDiscovery()
        socket?.close(1000, "bye")
        socket = null
        authorized = false
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        setState(State.Idle)
    }

    override fun show(text: String) {
        if (authorized) socket?.send(TvProtocol.subtitle(text))
    }

    override fun clear() {
        if (authorized) socket?.send(TvProtocol.clear())
    }

    // ---------- keşif ----------

    private fun startDiscovery() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovery = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.startsWith("_oyunceviri._tcp")) resolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        discovery = listener
        try {
            nsd.discoverServices(TvProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            discovery = null
        }
    }

    private fun stopDiscovery() {
        val l = discovery ?: return
        discovery = null
        try {
            nsd.stopServiceDiscovery(l)
        } catch (_: Exception) {
        }
    }

    // NsdManager aynı anda tek resolve kabul eder
    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        if (!running || socket != null || !resolving.compareAndSet(false, true)) return
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving.set(false)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolving.set(false)
                    val host = serviceInfo.host?.hostAddress?.substringBefore('%') ?: return
                    lastHost = host
                    lastPort = serviceInfo.port
                    connect(host, serviceInfo.port)
                }
            })
        } catch (e: Exception) {
            resolving.set(false)
        }
    }

    // ---------- bağlantı ----------

    private fun connect(host: String, port: Int) {
        if (!running || socket != null) return
        val url = try {
            HttpUrl.Builder().scheme("http").host(host).port(port)
                .addQueryParameter("token", token).build()
        } catch (e: IllegalArgumentException) {
            return
        }
        setState(State.Connecting(host))
        client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket != null) { // aynı anda iki bağlantı açıldıysa fazlayı kapat
                    webSocket.close(1000, "dup")
                    return
                }
                socket = webSocket
                authorized = false // TV "hello" diyene kadar bağlı sayma
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket === webSocket && TvProtocol.isHello(text)) {
                    authorized = true
                    failures = 0
                    webSocket.send(styleMessage())
                    setState(State.Connected(host))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, code)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket, -1)
        })
    }

    private fun lost(ws: WebSocket, code: Int) {
        if (socket === ws) {
            socket = null
            authorized = false
        }
        if (!running) return
        if (code == TvProtocol.CLOSE_UNAUTHORIZED) {
            running = false // yanlış kodla tekrar denemenin anlamı yok
            stopDiscovery()
            setState(State.Unauthorized)
            return
        }
        setState(State.Lost)
        failures++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(minOf(2_000L * failures, 15_000L))
            val host = lastHost
            if (failures >= 3 || host == null) {
                // IP değişmiş olabilir: keşfi baştan başlat
                lastHost = null
                stopDiscovery()
                setState(State.Searching)
                startDiscovery()
            } else if (running && socket == null) {
                connect(host, lastPort)
            }
        }
    }

    private fun setState(s: State) {
        _state.value = s
        SessionBus.setTvState(s)
    }
}
