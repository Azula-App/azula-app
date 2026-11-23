package dev.azula.selfgrant

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * The adb message codec and the TLS-era connect handshake + shell service
 * (AOSP system/core/adb/protocol.txt). Wireless debugging always uses TLS:
 * CNXN, then the device answers STLS, we answer STLS and the caller upgrades
 * the socket, then the device sends CNXN. The legacy RSA AUTH path is not
 * implemented. Reference for the sequence: Shizuku's AdbClient (Apache-2.0).
 */
internal object Adb {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534C5453

    const val VERSION = 0x01000000
    const val STLS_VERSION = 0x01000000
    const val MAX_DATA = 4096
    const val HEADER_SIZE = 24

    class AdbException(message: String) : Exception(message)

    class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0)) {
        fun encode(): ByteArray = ByteBuffer.allocate(HEADER_SIZE + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1)
            .putInt(data.size).putInt(checksum(data)).putInt(command xor -1)
            .put(data).array()

        override fun toString() = "Message(${name(command)}, $arg0, $arg1, ${data.size}B)"

        companion object {
            fun text(command: Int, arg0: Int, arg1: Int, s: String) =
                Message(command, arg0, arg1, "$s\u0000".toByteArray(Charsets.UTF_8))
        }
    }

    fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xff
        return sum
    }

    fun name(command: Int) = when (command) {
        A_SYNC -> "SYNC"; A_CNXN -> "CNXN"; A_AUTH -> "AUTH"; A_OPEN -> "OPEN"
        A_OKAY -> "OKAY"; A_CLSE -> "CLSE"; A_WRTE -> "WRTE"; A_STLS -> "STLS"
        else -> "0x" + Integer.toHexString(command)
    }

    fun read(input: DataInputStream): Message {
        val h = ByteBuffer.wrap(ByteArray(HEADER_SIZE).also { input.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
        val command = h.int
        val arg0 = h.int
        val arg1 = h.int
        val length = h.int
        h.int // data checksum: not enforced, newer adb versions don't send one
        val magic = h.int
        if (command != (magic xor -1)) throw AdbException("bad adb magic for ${name(command)}")
        if (length < 0 || length > 1 shl 20) throw AdbException("bad adb payload length $length")
        return Message(command, arg0, arg1, ByteArray(length).also { input.readFully(it) })
    }

    fun write(output: OutputStream, m: Message) {
        output.write(m.encode())
        output.flush()
    }

    class Streams(val input: InputStream, val output: OutputStream)

    /**
     * Connects (CNXN/STLS/CNXN), runs [command] on the `shell:` service and
     * returns its output. [upgradeToTls] is called once, after our STLS reply,
     * and must return the encrypted streams over the same socket.
     */
    fun shell(plain: Streams, upgradeToTls: () -> Streams, command: String): String {
        write(plain.output, Message.text(A_CNXN, VERSION, MAX_DATA, "host::"))
        var io = plain
        var din = DataInputStream(io.input)
        var m = read(din)
        if (m.command != A_STLS) {
            throw AdbException("device did not offer TLS (answered ${name(m.command)})")
        }
        write(io.output, Message(A_STLS, STLS_VERSION, 0))
        io = upgradeToTls()
        din = DataInputStream(io.input)
        m = read(din)
        if (m.command != A_CNXN) throw AdbException("expected CNXN after TLS, got ${name(m.command)}")

        val localId = 1
        write(io.output, Message.text(A_OPEN, localId, 0, "shell:$command"))
        m = read(din)
        if (m.command == A_CLSE) throw AdbException("shell service refused")
        if (m.command != A_OKAY) throw AdbException("expected OKAY, got ${name(m.command)}")
        val remoteId = m.arg0
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            m = read(din)
            when (m.command) {
                A_WRTE -> {
                    out.write(m.data)
                    write(io.output, Message(A_OKAY, localId, remoteId))
                }
                A_CLSE -> {
                    write(io.output, Message(A_CLSE, localId, remoteId))
                    break
                }
                A_OKAY -> Unit
                else -> throw AdbException("unexpected ${name(m.command)} in shell stream")
            }
        }
        return out.toString(Charsets.UTF_8)
    }
}
