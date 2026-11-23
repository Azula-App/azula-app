package dev.azula.vmhost

import android.os.ParcelFileDescriptor

/** Opens host-to-guest vsock connections (backed by the running VirtualMachine). */
internal fun interface VsockConnector {
    @Throws(Exception::class)
    fun connectVsock(port: Long): ParcelFileDescriptor
}
