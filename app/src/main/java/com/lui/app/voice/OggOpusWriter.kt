package com.lui.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal Ogg/Opus page writer for streaming.
 * Wraps raw Opus packets into Ogg pages suitable for PersonaPlex's moshi.server.
 *
 * Ogg page structure: https://www.xiph.org/ogg/doc/framing.html
 * Opus in Ogg: https://tools.ietf.org/html/rfc7845
 */
class OggOpusWriter(private val sampleRate: Int = 24000, private val channels: Int = 1) {

    private var serialNo: Int = (Math.random() * Int.MAX_VALUE).toInt()
    private var pageSequence: Int = 0
    private var granulePosition: Long = 0
    private val output = mutableListOf<ByteArray>()

    /**
     * Get the initial Ogg pages (OpusHead + OpusTags headers).
     * Must be sent before any audio pages.
     */
    fun getHeaderPages(): ByteArray {
        val pages = ByteBuffer.allocate(4096)

        // Page 1: OpusHead
        val opusHead = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        opusHead.put("OpusHead".toByteArray())
        opusHead.put(1) // version
        opusHead.put(channels.toByte())
        opusHead.putShort(0) // pre-skip
        opusHead.putInt(sampleRate) // input sample rate
        opusHead.putShort(0) // output gain
        opusHead.put(0) // channel mapping family
        val headData = opusHead.array()

        val headPage = buildPage(headData, granulePos = 0, headerType = 0x02) // BOS flag
        pages.put(headPage)

        // Page 2: OpusTags (minimal)
        val vendor = "LUI"
        val tagsSize = 8 + 4 + vendor.length + 4
        val opusTags = ByteBuffer.allocate(tagsSize).order(ByteOrder.LITTLE_ENDIAN)
        opusTags.put("OpusTags".toByteArray())
        opusTags.putInt(vendor.length)
        opusTags.put(vendor.toByteArray())
        opusTags.putInt(0) // no user comments
        val tagsData = ByteArray(opusTags.position())
        opusTags.flip()
        opusTags.get(tagsData)

        val tagsPage = buildPage(tagsData, granulePos = 0, headerType = 0x00)
        pages.put(tagsPage)

        val result = ByteArray(pages.position())
        pages.flip()
        pages.get(result)
        return result
    }

    /**
     * Wrap a raw Opus packet into an Ogg page.
     * @param opusPacket raw Opus encoded data from MediaCodec
     * @param samplesInPacket number of PCM samples this packet represents (typically 480 for 20ms at 24kHz)
     * @return Ogg page bytes ready to send
     */
    fun writePacket(opusPacket: ByteArray, samplesInPacket: Int = 480): ByteArray {
        granulePosition += samplesInPacket
        return buildPage(opusPacket, granulePos = granulePosition, headerType = 0x00)
    }

    private fun buildPage(data: ByteArray, granulePos: Long, headerType: Int): ByteArray {
        // Calculate segment table
        val segments = mutableListOf<Int>()
        var remaining = data.size
        while (remaining >= 255) {
            segments.add(255)
            remaining -= 255
        }
        segments.add(remaining) // final segment (0-254)

        val headerSize = 27 + segments.size
        val pageSize = headerSize + data.size
        val page = ByteBuffer.allocate(pageSize).order(ByteOrder.LITTLE_ENDIAN)

        // Ogg page header
        page.put("OggS".toByteArray()) // capture pattern
        page.put(0) // stream structure version
        page.put(headerType.toByte()) // header type flag
        page.putLong(granulePos) // granule position
        page.putInt(serialNo) // stream serial number
        page.putInt(pageSequence++) // page sequence number
        page.putInt(0) // CRC placeholder (position 22)
        page.put(segments.size.toByte()) // number of segments

        // Segment table
        for (s in segments) page.put(s.toByte())

        // Payload
        page.put(data)

        // Calculate and set CRC
        val pageBytes = page.array()
        val crc = oggCrc(pageBytes)
        pageBytes[22] = (crc and 0xFF).toByte()
        pageBytes[23] = ((crc shr 8) and 0xFF).toByte()
        pageBytes[24] = ((crc shr 16) and 0xFF).toByte()
        pageBytes[25] = ((crc shr 24) and 0xFF).toByte()

        return pageBytes
    }

    companion object {
        // Ogg CRC32 lookup table
        private val crcTable = IntArray(256).also { table ->
            for (i in 0..255) {
                var r = i shl 24
                for (j in 0..7) {
                    r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7
                    else r shl 1
                }
                table[i] = r
            }
        }

        fun oggCrc(data: ByteArray): Int {
            var crc = 0
            for (b in data) {
                crc = (crc shl 8) xor crcTable[((crc ushr 24) and 0xFF) xor (b.toInt() and 0xFF)]
            }
            return crc
        }
    }
}
