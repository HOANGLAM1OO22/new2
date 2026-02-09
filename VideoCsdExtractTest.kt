package com.example.transition

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.transition.video.AvcConfig
import com.example.transition.video.AvcPpsParser
import com.example.transition.video.AvcSpsInfo
import com.example.transition.video.AvcSpsParser
import com.example.transition.video.HevcConfig
import com.example.transition.video.HevcPpsParser
import com.example.transition.video.HevcSpsParser
import com.example.transition.video.HevcVpsParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class VideoCsdExtractTest {
    @get:Rule
    val permissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.READ_MEDIA_VIDEO,
        android.Manifest.permission.READ_EXTERNAL_STORAGE,
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    @Test
    fun extractCsdFromVideo() {
//        val videoPath = "/sdcard/Pictures/test/mp1.mp4"
        val videoPath = "/sdcard/Pictures/test/avc.mp4"
        val extractor = MediaExtractor()
        extractor.setDataSource(videoPath)

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

            if (mime.startsWith("video/")) {
                Log.d(TAG, "Video track mime=$mime")
                printCsd(format, mime)
            }
        }

        extractor.release()
    }

    companion object {
        private const val TAG = "VideoCsdTest"
    }

    @Test
    fun transcodeMp1WithAvcCodecConfig_bufferBased() {

        val refPath = "/sdcard/Pictures/test/avc.mp4"
        val srcPath = "/sdcard/Pictures/test/mp1.mp4"
        val outPath = "/sdcard/Pictures/test/out_from_mp1_with_avc_cfg.mp4"

        // --------------------------------------------------
        // 1. Extract AVC SPS from reference video
        // --------------------------------------------------
        val refExtractor = MediaExtractor()
        refExtractor.setDataSource(refPath)
        val refTrack = selectVideoTrack(refExtractor)
        val refFormat = refExtractor.getTrackFormat(refTrack)

        val refSps = refFormat.getByteBuffer("csd-0")!!.toByteArray()
        val refInfo = AvcSpsParser.parseAndReturn(refSps)
        refExtractor.release()

        // --------------------------------------------------
        // 2. Prepare source extractor
        // --------------------------------------------------
        val srcExtractor = MediaExtractor()
        srcExtractor.setDataSource(srcPath)
        val srcTrack = selectVideoTrack(srcExtractor)
        val srcFormat = srcExtractor.getTrackFormat(srcTrack)
        srcExtractor.selectTrack(srcTrack)

        // --------------------------------------------------
        // 3. Create decoder (BUFFER MODE)
        // --------------------------------------------------
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        decoder.configure(srcFormat, null, null, 0)
        decoder.start()

        // --------------------------------------------------
        // 4. Create encoder (BUFFER MODE, from ref SPS)
        // --------------------------------------------------
        val encodeFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            refInfo.width,
            refInfo.height
        )
        encodeFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
        encodeFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        encodeFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        encodeFormat.setInteger(
            MediaFormat.KEY_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        )
        encodeFormat.setInteger(
            MediaFormat.KEY_LEVEL,
            MediaCodecInfo.CodecProfileLevel.AVCLevel4
        )

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        // --------------------------------------------------
        // 5. Prepare muxer
        // --------------------------------------------------
        val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var videoTrackIndex = -1

        val TIMEOUT_US = 10_000L
        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()

        var extractorDone = false
        var decoderDone = false
        var encoderDone = false

        // --------------------------------------------------
        // 6. Decode → Encode loop
        // --------------------------------------------------
        while (!encoderDone) {

            // ---- feed decoder ----
            if (!extractorDone) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuf = decoder.getInputBuffer(inIndex)!!
                    val size = srcExtractor.readSampleData(inBuf, 0)

                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        extractorDone = true
                    } else {
                        decoder.queueInputBuffer(
                            inIndex, 0, size,
                            srcExtractor.sampleTime, 0
                        )
                        srcExtractor.advance()
                    }
                }
            }

            // ---- drain decoder ----
            var encoderEosSent = false

            if (!decoderDone) {
                val outIndex = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIndex)!!

                    if (!encoderEosSent) {
                        val encInIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (encInIndex >= 0) {
                            val encInBuf = encoder.getInputBuffer(encInIndex)!!
                            encInBuf.clear()
                            encInBuf.put(outBuf)

                            val flags = if (
                                decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            ) {
                                encoderEosSent = true
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            } else {
                                0
                            }

                            encoder.queueInputBuffer(
                                encInIndex,
                                0,
                                decInfo.size,
                                decInfo.presentationTimeUs,
                                flags
                            )
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                    }

                    if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoderDone = true
                    }
                }
            }


            // ---- drain encoder ----
            val encOutIndex = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
            when {
                encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = encoder.outputFormat
                    videoTrackIndex = muxer.addTrack(outFormat)
                    muxer.start()
                    muxerStarted = true
                }

                encOutIndex >= 0 -> {
                    if (encInfo.size > 0 && muxerStarted) {
                        val encOutBuf = encoder.getOutputBuffer(encOutIndex)!!
                        muxer.writeSampleData(videoTrackIndex, encOutBuf, encInfo)
                    }

                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoderDone = true
                    }
                    encoder.releaseOutputBuffer(encOutIndex, false)
                }
            }
        }

        // --------------------------------------------------
        // 7. Cleanup
        // --------------------------------------------------
        decoder.stop()
        decoder.release()
        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()
        srcExtractor.release()
    }
    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i)
                .getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) return i
        }
        error("No video track found")
    }
    private fun createAvcEncoder(
        cfg: AvcSpsInfo,
        bitrate: Int,
        fps: Int
    ): EncoderWithSurface {

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            cfg.width,
            cfg.height
        )

        format.setInteger(
            MediaFormat.KEY_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        )
        format.setInteger(
            MediaFormat.KEY_LEVEL,
            MediaCodecInfo.CodecProfileLevel.AVCLevel4
        )
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        return EncoderWithSurface(encoder, inputSurface)
    }

    data class EncoderWithSurface(
        val encoder: MediaCodec,
        val inputSurface: Surface
    )


    private fun printCsd(format: MediaFormat, mime: String) {
        var index = 0
        while (true) {
            val key = "csd-$index"
            if (!format.containsKey(key)) break

            val csd = format.getByteBuffer(key)!!
            val data = ByteArray(csd.remaining())
            csd.get(data)

            Log.d(TAG, "===== $mime $key =====")
            Log.d(TAG, bytesToHex(data))

            if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                parseAvcNal(data)
            } else if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
               // parseHevcNal(data)
                parseHevcCsd(data)
            }

            index++
        }
    }
    private fun parseAvcNal(data: ByteArray) {
        val nalType = data[4].toInt() and 0x1F
        when (nalType) {
            7 -> {
                Log.d(TAG, "AVC SPS detected")
                AvcSpsParser.parse(data)
            }
            8 -> {
                Log.d(TAG, "AVC PPS detected")
                AvcPpsParser.parse(data)
            }
        }
    }

    private fun parseHevcCsd(csd: ByteArray) {
        val nals = splitAnnexBNals(csd)

        for (nal in nals) {
            val nalType = (nal[4].toInt() shr 1) and 0x3F

            when (nalType) {
                32 -> {
                    Log.d(TAG, "HEVC VPS detected")
                    HevcVpsParser.parse(nal)
                }
                33 -> {
                    Log.d(TAG, "HEVC SPS detected")
                    HevcSpsParser.parse(nal)
                }
                34 -> {
                    Log.d(TAG, "HEVC PPS detected")
                    HevcPpsParser.parse(nal)
                }
                else -> Log.d(TAG, "HEVC NAL type=$nalType")
            }
        }
    }

    private fun parseHevcNal(data: ByteArray) {
        val nalType = (data[4].toInt() shr 1) and 0x3F
        when (nalType) {
            32 -> Log.d(TAG, "HEVC VPS detected")
            33 -> {
                Log.d(TAG, "HEVC SPS detected")
                HevcSpsParser.parse(data)
            }
            34 -> Log.d(TAG, "HEVC PPS detected")
        }
    }

    fun splitAnnexBNals(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var start = -1
        var i = 0

        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() &&
                data[i + 3] == 1.toByte()
            ) {
                if (start >= 0) {
                    nals.add(data.copyOfRange(start, i))
                }
                start = i
                i += 4
            } else {
                i++
            }
        }

        if (start >= 0 && start < data.size) {
            nals.add(data.copyOfRange(start, data.size))
        }

        return nals
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    ///
    fun extractAvcConfig(extractor: MediaExtractor, trackIndex: Int): AvcConfig {
        val format = extractor.getTrackFormat(trackIndex)

        val sps = format.getByteBuffer("csd-0")!!.toByteArray()
        val pps = format.getByteBuffer("csd-1")!!.toByteArray()

        val parsed = AvcSpsParser.parseAndReturn(sps)

        return AvcConfig(
            profile = parsed.profileIdc,
            level = parsed.levelIdc,
            width = parsed.width,
            height = parsed.height,
            sps = sps,
            pps = pps
        )
    }
    fun extractHevcConfig(extractor: MediaExtractor, trackIndex: Int): HevcConfig {
        val format = extractor.getTrackFormat(trackIndex)

        val csd0 = format.getByteBuffer("csd-0")!!.toByteArray()
        val nals = splitAnnexBNals(csd0)

        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        for (nal in nals) {
            when ((nal[4].toInt() shr 1) and 0x3F) {
                32 -> vps = nal
                33 -> sps = nal
                34 -> pps = nal
            }
        }

        val parsed = HevcSpsParser.parseAndReturn(sps!!)

        return HevcConfig(
            profile = parsed.profileIdc,
            level = parsed.levelIdc,
            width = parsed.width,
            height = parsed.height,
            vps = vps,
            sps = sps,
            pps = pps!!
        )
    }
    fun ByteBuffer.toByteArray(): ByteArray {
        val dup = this.duplicate()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        return bytes
    }

    fun createDecoder(format: MediaFormat): MediaCodec {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        return MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    fun createAvcEncoder(cfg: AvcConfig, bitrate: Int, fps: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            cfg.width,
            cfg.height
        )

        format.setInteger(
            MediaFormat.KEY_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        )
        format.setInteger(
            MediaFormat.KEY_LEVEL,
            MediaCodecInfo.CodecProfileLevel.AVCLevel4
        )
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    fun createHevcEncoder(cfg: HevcConfig, bitrate: Int, fps: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            cfg.width,
            cfg.height
        )

        format.setInteger(
            MediaFormat.KEY_PROFILE,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        )
        format.setInteger(
            MediaFormat.KEY_LEVEL,
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4
        )
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

}
