@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.foodics.crosscommunicationlibrary.qr

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.*
import kotlin.concurrent.Volatile

actual class QRServerHandler {

    private companion object {
        const val BACKLOG = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _qrCodeBytes = MutableStateFlow<ByteArray?>(null)
    val qrCodeBytes: StateFlow<ByteArray?> = _qrCodeBytes.asStateFlow()

    @Volatile private var serverFd = -1
    @Volatile private var clientFd = -1
    private var acceptJob: Job? = null
    private val _fromClient = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    fun start(deviceName: String, identifier: String) {
        stop()

        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "[QRServer] socket() failed errno=$errno" }
        serverFd = fd

        val port = memScoped {
            val flag = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, flag.ptr, sizeOf<IntVar>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = qrHtons(0u)
            addr.sin_addr.s_addr = INADDR_ANY
            check(bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) {
                "[QRServer] bind() failed errno=$errno"
            }

            val bound = alloc<sockaddr_in>()
            val boundLen = alloc<UIntVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            getsockname(fd, bound.ptr.reinterpret(), boundLen.ptr)
            qrNtohs(bound.sin_port).toInt()
        }

        check(listen(fd, BACKLOG) == 0) { "[QRServer] listen() failed errno=$errno" }

        val ip = getWifiIpAddress()
        val json = buildQRJsonIos(identifier, deviceName, ip, port)
        println("[QRServer] Payload: $json")

        // Generate QR via Swift bridge
        _qrCodeBytes.value = QRBridgeProvider.generatorBridge
            ?.generateQRCode(json)
            ?.toKByteArray()

        acceptJob = scope.launch {
            while (isActive && serverFd >= 0) {
                val cfd = accept(serverFd, null, null)
                if (cfd < 0) {
                    if (isActive) println("[QRServer] accept() errno=$errno")
                    continue
                }
                val old = clientFd
                if (old >= 0) close(old)
                clientFd = cfd
                println("[QRServer] Client connected")
                startReceiveLoop(cfd)
            }
        }
        println("[QRServer] Listening on $ip:$port")
    }

    fun sendToClient(data: ByteArray) {
        val fd = clientFd
        if (fd < 0) { println("[QRServer] No client connected"); return }
        tcpSendFramed(fd, data)
    }

    fun receiveFromClient(): Flow<ByteArray> = _fromClient.asSharedFlow()

    fun stop() {
        acceptJob?.cancel()
        val cfd = clientFd; clientFd = -1; if (cfd >= 0) close(cfd)
        val sfd = serverFd; serverFd = -1; if (sfd >= 0) close(sfd)
        _qrCodeBytes.value = null
        println("[QRServer] Stopped")
    }

    private fun startReceiveLoop(fd: Int) {
        scope.launch {
            while (isActive && fd >= 0) {
                val data = tcpRecvFramed(fd) ?: break
                _fromClient.tryEmit(data)
            }
            println("[QRServer] Receive loop ended for fd=$fd")
        }
    }

    private fun getWifiIpAddress(): String = memScoped {
        val ifaddrPtr = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(ifaddrPtr.ptr) != 0) return "0.0.0.0"
        var ip = "0.0.0.0"
        var ptr: CPointer<ifaddrs>? = ifaddrPtr.value
        while (ptr != null) {
            val ifa = ptr.pointed
            val sa = ifa.ifa_addr
            if (sa != null && sa.pointed.sa_family.toInt() == AF_INET) {
                if (ifa.ifa_name?.toKString() == "en0") {
                    ip = qrInetNtop(sa.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr)
                    break
                }
            }
            ptr = ifa.ifa_next
        }
        freeifaddrs(ifaddrPtr.value)
        ip
    }
}

/** UInt s_addr (network byte order) → dotted-decimal string. */
private fun qrInetNtop(sAddr: UInt): String {
    val b0 =  sAddr         and 0xFFu
    val b1 = (sAddr shr  8) and 0xFFu
    val b2 = (sAddr shr 16) and 0xFFu
    val b3 = (sAddr shr 24) and 0xFFu
    return "$b0.$b1.$b2.$b3"
}
