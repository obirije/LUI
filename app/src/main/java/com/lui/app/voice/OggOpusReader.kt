package com.lui.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal Ogg page reader — extracts raw Opus packets from Ogg/Opus stream pages.
 * Handles streaming input (partial pages buffered until complete).
 */
class OggOpusReader {

    private val buffer = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN)
    private var headersParsed = false

    init {
        buffer.limit(0) // empty initially
    }

    /**
     * Feed incoming Ogg data and extract Opus audio packets.
     * Skips OpusHead and OpusTags header pages.
     * @return list of raw Opus packets extracted from complete pages
     */
    fun feed(data: ByteArray): List<ByteArray> {
        // Append to buffer
        val oldPos = buffer.position()
        val oldLimit = buffer.limit()
        buffer.position(oldLimit)
        buffer.limit(buffer.capacity())
        buffer.put(data, 0, minOf(data.size, buffer.remaining()))
        buffer.flip()

        val packets = mutableListOf<ByteArray>()

        while (true) {
            val pageStart = findOggPage(buffer)
            if (pageStart < 0) break

            buffer.position(pageStart)
            if (buffer.remaining() < 27) break // need at least header

            // Read header fields
            buffer.position(pageStart + 5) // skip "OggS" + version
            val headerType = buffer.get().toInt() and 0xFF
            val granulePos = buffer.long
            val serialNo = buffer.int
            val pageSeq = buffer.int
            val crc = buffer.int
            val numSegments = buffer.get().toInt() and 0xFF

            if (buffer.remaining() < numSegments) {
                buffer.position(pageStart)
                break
            }

            // Read segment table
            var totalDataSize = 0
            val segmentSizes = IntArray(numSegments)
            for (i in 0 until numSegments) {
                segmentSizes[i] = buffer.get().toInt() and 0xFF
                totalDataSize += segmentSizes[i]
            }

            if (buffer.remaining() < totalDataSize) {
                buffer.position(pageStart)
                break // incomplete page
            }

            // Read page data
            val pageData = ByteArray(totalDataSize)
            buffer.get(pageData)

            // Skip header pages (OpusHead, OpusTags)
            if (!headersParsed) {
                if (headerType and 0x02 != 0 || // BOS flag
                    pageData.size >= 8 && String(pageData, 0, 8) == "OpusHead" ||
                    pageData.size >= 8 && String(pageData, 0, 8) == "OpusTags") {
                    if (pageData.size >= 8 && String(pageData, 0, 8) == "OpusTags") {
                        headersParsed = true
                    }
                    continue
                }
                headersParsed = true
            }

            // Extract Opus packets from segments
            var offset = 0
            val packetAccum = mutableListOf<Byte>()
            for (segSize in segmentSizes) {
                for (i in 0 until segSize) {
                    packetAccum.add(pageData[offset + i])
                }
                offset += segSize
                if (segSize < 255) {
                    // End of packet
                    if (packetAccum.isNotEmpty()) {
                        packets.add(packetAccum.toByteArray())
                    }
                    packetAccum.clear()
                }
            }
        }

        // Compact buffer
        buffer.compact()
        buffer.flip()

        return packets
    }

    private fun findOggPage(buf: ByteBuffer): Int {
        val pos = buf.position()
        for (i in pos until buf.limit() - 3) {
            if (buf.get(i) == 'O'.code.toByte() &&
                buf.get(i + 1) == 'g'.code.toByte() &&
                buf.get(i + 2) == 'g'.code.toByte() &&
                buf.get(i + 3) == 'S'.code.toByte()) {
                return i
            }
        }
        return -1
    }
}
