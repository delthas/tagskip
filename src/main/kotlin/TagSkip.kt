@file:JvmName("TagSkipUtil")
@file:JvmMultifileClass

package fr.delthas.tagskip

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

private val bitrates = arrayOf(
        intArrayOf(32000, 64000, 96000, 128000, 160000, 192000, 224000, 256000, 288000, 320000, 352000, 384000, 416000, 448000),
        intArrayOf(32000, 48000, 56000, 64000, 80000, 96000, 112000, 128000, 160000, 192000, 224000, 256000, 320000, 384000),
        intArrayOf(32000, 40000, 48000, 56000, 64000, 80000, 96000, 112000, 128000, 160000, 192000, 224000, 256000, 320000),
        intArrayOf(32000, 48000, 56000, 64000, 80000, 96000, 112000, 128000, 144000, 160000, 176000, 192000, 224000, 256000),
        intArrayOf(8000, 16000, 24000, 32000, 40000, 48000, 56000, 64000, 80000, 96000, 112000, 128000, 144000, 160000)
)

private val samplingRates = arrayOf(
        intArrayOf(44100, 48000, 32000),
        intArrayOf(22050, 24000, 16000),
        intArrayOf(11025, 12000, 8000)
)

private inline fun Logger.warn(f: () -> String) {
    if(isWarnEnabled) {
        warn(f())
    }
}

private inline fun Logger.debug(f: () -> String) {
    if(isDebugEnabled) {
        debug(f())
    }
}

private inline fun Logger.trace(f: () -> String) {
    if(isTraceEnabled) {
        trace(f())
    }
}

private val logger = LoggerFactory.getLogger(TagSkipInputStream::class.java)

/**
 * TagSkipInputStream forwards data from an underlying input stream,
 * but skips over any metadata tags (IDv3, APE, ...).
 * <p>
 * The underlying input stream must contain only MPEG audio frames
 * and metadata tags, which in most cases will be the content of
 * an MP3 file.
 * <p>
 * TagSkipInputStream can read from a sequence of MP3 files, i.e.
 * you don't need to create a new stream for every file you read.
 * <p>
 * Marks are not supported.
 */
class TagSkipInputStream(private val sin: InputStream): InputStream() {
    private val buf = ByteArray(40) // size must not be changed
    private var bufOff = buf.size
    private var skip = 0
    private var copy = 0
    private var eof = 0 // 0: not eof, 1: eof, 2: unexpected eof
    private var closed = false
    private var desync = 0

    private fun readHeader(): Boolean {
        if(bufOff < buf.size) {
            System.arraycopy(buf, bufOff, buf, 0, buf.size - bufOff)
            bufOff = buf.size - bufOff
        } else {
            bufOff = 0
        }
        while(bufOff < buf.size) {
            val n = sin.read(buf, bufOff, buf.size - bufOff)
            if(n == -1) {
                if(bufOff == 0) {
                    eof = 1
                    return true
                }
                if(bufOff < 32) {
                    // MPEG frames are all >= 32 bytes
                    // if less than 32 bytes remain in the stream, it cannot be an MPEG frame
                    // return eof and permanently ignore any remaining bytes from the stream
                    eof = 2
                    return true
                }
                break
            }
            bufOff += n
        }

        var updatedDesync = false

        logger.trace { "parsing header: ${buf.joinToString(prefix = "[", postfix = "]") { Integer.toHexString(it.toInt() and 0xFF) } }" }
        if(buf[0] == 0b11111111.toByte() && ((buf[1].toInt() and 0b11100000 == 0b11100000))) { // MPEG frame
            logger.debug { "MPEG header" }
            val version = (buf[1].toInt().inv() and 0b00001000).ushr(3) + (buf[1].toInt().inv() and 0b00010000).ushr(4)
            val layer = 3 - ((buf[1].toInt() and 0b00000110) ushr 1)
            val bitrateIndex = (buf[2].toInt() and 0b11110000) ushr 4
            val samplingIndex = (buf[2].toInt() and 0b00001100) ushr 2
            val padding = (buf[2].toInt() and 0b00000010) ushr 1
            val stereo = (buf[3].toInt() and 0b11000000) ushr 6
            if(layer < 0 || bitrateIndex == 0b0000 || bitrateIndex == 0b1111 || samplingIndex == 0b11) {
                throw IOException("couldn't parse mp3 stream: invalid mp3 header")
            } else {
                val bitrateTable = when {
                    version == 0 -> layer
                    layer == 0 -> 3
                    else -> 4
                }
                val bitrate = bitrates[bitrateTable][bitrateIndex - 1]
                val sampleRate = samplingRates[version][samplingIndex]
                val size = when(layer) {
                    0 -> (12 * bitrate / sampleRate + padding) * 4
                    1 -> 144 * bitrate / sampleRate + padding
                    2 -> (if(version == 0) 144 else 72) * bitrate / sampleRate + padding
                    else -> throw AssertionError()
                }
                val xing = if(version == 0) {
                    if(stereo != 3) 36 else 21
                } else {
                    if(stereo != 3) 21 else 13
                }
                if(desync == size) {
                    // don't warn on a single invalid frame
                    desync = 0
                }
                if(xing + 4 <= bufOff &&
                        ((buf[xing] == 'X'.toByte() && buf[xing + 1] == 'i'.toByte() && buf[xing + 2] == 'n'.toByte() && buf[xing + 3] == 'g'.toByte()) ||
                                (buf[xing] == 'I'.toByte() && buf[xing + 1] == 'n'.toByte() && buf[xing + 2] == 'f'.toByte() && buf[xing + 3] == 'o'.toByte()) ||
                                (buf[xing] == 'L'.toByte() && buf[xing + 1] == 'A'.toByte() && buf[xing + 2] == 'M'.toByte() && buf[xing + 3] == 'E'.toByte()))) {
                    logger.debug { "skipping dummy ${buf[xing].toChar()}${buf[xing + 1].toChar()}${buf[xing + 2].toChar()}${buf[xing + 3].toChar()} frame" }
                    skip = size
                } else {
                    copy = size
                }
            }
        } else if(buf[0] == 'I'.toByte() && buf[1] == 'D'.toByte() && buf[2] == '3'.toByte()) { // ID3v2
            logger.debug { "ID3v2 header" }
            skip = 10
            if((buf[5].toInt() and 0b00010000) != 0) {
                logger.trace { "ID3v2 header has footer" }
                skip += 10
            }
            skip += (buf[6].toInt() and 0b01111111) shl 21
            skip += (buf[7].toInt() and 0b01111111) shl 14
            skip += (buf[8].toInt() and 0b01111111) shl 7
            skip += buf[9].toInt() and 0b01111111
        } else if(buf[0] == 'A'.toByte() && buf[1] == 'P'.toByte() && buf[2] == 'E'.toByte() && buf[3] == 'T'.toByte() && buf[4] == 'A'.toByte() && buf[5] == 'G'.toByte() && buf[6] == 'E'.toByte() && buf[7] == 'X'.toByte()) { // APEv2
            logger.debug { "APEv2 header" }
            var version = 0
            version += buf[8].toInt() and 0xFF
            version += (buf[9].toInt() and 0xFF) shl 8
            version += (buf[10].toInt() and 0xFF) shl 16
            version += (buf[11].toInt() and 0xFF) shl 24
            if(version != 2000) {
                throw IOException("couldn't parse mp3 stream: unknown APEv2 header tag version: $version")
            }
            skip = 32
            skip += buf[12].toInt() and 0xFF
            skip += (buf[13].toInt() and 0xFF) shl 8
            skip += (buf[14].toInt() and 0xFF) shl 16
            skip += (buf[15].toInt() and 0xFF) shl 24
        } else if(buf[0] == 'T'.toByte() && buf[1] == 'A'.toByte() && buf[2] == 'G'.toByte()) { // ID3v1
            logger.debug { "ID3v1 header" }
            if(buf[3] == '+'.toByte()) { // Enhanced Tag
                // hopefully the song title doesn't start with a "+"
                skip = 227
            } else { // Regular ID3v1
                skip = 128
            }
        } else {
            // could be APEv1 (unlikely), not implemented
            if(buf[0] == 0.toByte()) {
                // skip zero bytes (up to one frame)
                skip = buf.indexOfFirst { it != 0.toByte() }.let { if(it == -1) buf.size else it }
                desync += skip
                if(desync > 2880 /* max mpeg frame size */) {
                    logger.warn { "desynced for too many bytes: $desync" }
                    throw IOException("couldn't parse mp3 stream: unknown header/tag")
                }
                updatedDesync = true
            } else {
                logger.warn { "unknown header" }
                throw IOException("couldn't parse mp3 stream: unknown header/tag")
            }
        }

        if(desync > 0 && !updatedDesync) {
            logger.warn { "skipped over $desync garbage 0-bytes" }
            desync = 0
        }

        bufOff = 0
        logger.debug { "copying $copy bytes, skipping $skip bytes" }

        return false
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if(closed) {
            throw IOException("stream is closed")
        }
        if(eof == 1) {
            return -1
        }
        if(eof == 2) {
            throw IOException("unexpected EOF")
        }
        var r = 0
        while(r < len) {
            if(copy > 0) {
                val n: Int
                if(bufOff < buf.size) {
                    n = min(buf.size - bufOff, len - r)
                    System.arraycopy(buf, bufOff, b, off + r, n)
                    bufOff += n
                    logger.trace { "copied $n bytes from buffer to output, remain ${buf.size - bufOff}" }
                } else {
                    val read = min(copy, len - r)
                    n = sin.read(b, off + r, read)
                    if(n == -1) {
                        eof = 2
                        return r
                    }
                    logger.trace { "copied $n bytes from stream to output, remain ${copy - n}" }
                }
                copy -= n
                r += n
                continue
            }
            if(skip > 0) {
                if(bufOff < buf.size) {
                    val n = min(buf.size - bufOff, skip)
                    bufOff += n
                    skip -= n
                    logger.trace { "skipped $n bytes from buffer, remain ${buf.size - bufOff}" }
                    continue
                }
                val n = sin.skip(skip.toLong())
                skip -= n.toInt()
                logger.trace { "skipped $n bytes from stream, remain $skip" }
                continue
            }

            if(readHeader()) {
                return r
            }
        }
        return r
    }

    override fun skip(len: Long): Long {
        if(closed) {
            throw IOException("stream is closed")
        }
        if(eof == 1 || len <= 0) {
            return 0
        }
        if(eof == 2) {
            throw IOException("unexpected EOF")
        }
        var r = 0L
        while(r < len) {
            if(copy > 0) {
                val n: Long
                if(bufOff < buf.size) {
                    n = min((buf.size - bufOff).toLong(), len - r)
                    bufOff += n.toInt()
                } else {
                    val skip = min(copy.toLong(), len - r)
                    n = sin.skip(skip)
                    if(n == 0L) {
                        return r
                    }
                }
                copy -= n.toInt()
                r += n
                continue
            }
            if(skip > 0) {
                if(bufOff < buf.size) {
                    val n = min(buf.size - bufOff, skip)
                    bufOff += n
                    skip -= n
                    continue
                }
                val n = sin.skip(skip.toLong())
                if(n == 0L) {
                    return r
                }
                skip -= n.toInt()
                continue
            }

            if(readHeader()) {
                return r
            }
        }
        return r
    }

    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b)
        if(n <= 0) {
            return -1
        }
        return b[0].toInt() and 0xFF
    }

    /**
     * Marks are not supported.
     *
     * @return false
     */
    override fun markSupported(): Boolean {
        return false
    }

    /**
     * Marks are not supported.
     */
    override fun mark(readlimit: Int) {
    }

    /**
     * Marks are not supported.
     */
    override fun reset() {
    }

    /**
     * Closes the underlying input stream.
     *
     * @exception IOException if the underlying input steam throws an I/O exception.
     */
    override fun close() {
        super.close()
        closed = true
    }
}
