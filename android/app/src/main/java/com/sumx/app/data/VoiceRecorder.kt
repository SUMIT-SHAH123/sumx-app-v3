package com.sumx.app.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

@Suppress("DEPRECATION")
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startMs = 0L

    fun start(): Boolean = try {
        val f = File.createTempFile("voice", ".m4a", ctx.cacheDir)
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setAudioEncodingBitRate(64_000); r.setAudioSamplingRate(44_100)
        r.setOutputFile(f.absolutePath); r.prepare(); r.start()
        rec = r; file = f; startMs = System.currentTimeMillis(); true
    } catch (e: Exception) { cancel(); false }

    /** Returns the recorded file and duration, or null if it was too short / failed. */
    fun stop(): Pair<File, Int>? {
        val r = rec ?: return null; val f = file; val dur = (System.currentTimeMillis() - startMs).toInt()
        rec = null; file = null
        return try { r.stop(); r.release(); if (f != null && dur >= 700) f to dur else { f?.delete(); null } }
        catch (e: Exception) { runCatching { r.release() }; f?.delete(); null }
    }
    fun cancel() { runCatching { rec?.stop() }; runCatching { rec?.release() }; rec = null; file?.delete(); file = null }
}
