package com.scamguard.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.scamguard.BuildConfig
import com.scamguard.MainActivity
import com.scamguard.R
import com.scamguard.judge.Band
import com.scamguard.judge.CallerPrior
import com.scamguard.judge.JudgeClient
import com.scamguard.profile.ProfileRepository
import com.scamguard.profile.VulnerabilityProfile
import com.scamguard.stt.GainControl
import com.scamguard.stt.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Listens to a call (speakerphone microphone) or plays a demo recording, and runs the same
 * pipeline on both: on-device speech-to-text -> redaction -> judge -> score.
 *
 * Audio is processed in memory in 100 ms pieces and discarded. Nothing is recorded to disk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallGuardService : Service() {

    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)
    private var run: Job? = null
    private var lastAlertBand = Band.LISTENING

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LIVE -> begin(Mode.LIVE, "Live call", null)
            ACTION_DEMO -> begin(Mode.DEMO, intent.getStringExtra(EXTRA_TITLE) ?: "Demo call", intent.getStringExtra(EXTRA_ASSET))
            else -> stopEverything()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Guard.ui.update { it.copy(active = false, preparing = false) }
        super.onDestroy()
    }

    private fun begin(mode: Mode, title: String, asset: String?) {
        createChannels(this)
        val type = if (mode == Mode.LIVE) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        try {
            ServiceCompat.startForeground(this, ONGOING_ID, ongoingNotification(mode), type)
        } catch (e: Exception) {
            // Android refuses a microphone service that was not started from something the user can see.
            Guard.ui.value = GuardUi(problem = "Open Scam Guard and tap Start listening.")
            stopSelf()
            return
        }
        val previous = run
        previous?.cancel()
        lastAlertBand = Band.LISTENING
        Guard.ui.value = GuardUi(active = true, mode = mode, title = title, preparing = true)

        run = scope.launch {
            previous?.join()
            try {
                val repo = ProfileRepository(this@CallGuardService)
                // Demos fall back to the sample profile so they work on a fresh install.
                val profile = repo.load() ?: if (mode == Mode.DEMO) VulnerabilityProfile.MOCK else null
                val savedUrl = repo.judgeUrlFlow.first()
                val prior = when {
                    mode == Mode.LIVE -> Guard.takePrior(SystemClock.elapsedRealtime())
                    asset != null -> DemoCalls.priorFor(asset)
                    else -> CallerPrior()
                }
                val model = SpeechEngine.model(this@CallGuardService)
                val client = JudgeClient(savedUrl ?: BuildConfig.JUDGE_URL, BuildConfig.JUDGE_URL)
                val session = GuardSession(this, { tier, text -> client.judge(tier, text, profile, prior) }, profile, prior, Guard.ui)
                Guard.ui.update { it.copy(preparing = false) }

                val watcher = launch { watchForAlerts() }
                SpeechEngine.Stream(model).use { stt ->
                    if (mode == Mode.LIVE) runMicrophone(stt, session) else runRecording(asset!!, stt, session)
                    stt.finish()?.let { session.onEvent(it) }
                }
                session.awaitIdle()
                watcher.cancel()
                Guard.ui.update { it.copy(active = false, finished = true) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Guard.ui.update { it.copy(active = false, preparing = false, problem = "Scam Guard could not start: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                if (isActive) {
                    ServiceCompat.stopForeground(this@CallGuardService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun stopEverything() {
        run?.cancel()
        Guard.ui.update { it.copy(active = false, preparing = false, finished = it.checks > 0) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- live: speakerphone microphone ------------------------------------------------------

    private suspend fun runMicrophone(stt: SpeechEngine.Stream, session: GuardSession) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Guard.ui.update { it.copy(problem = "Scam Guard needs the microphone to hear the call. Allow it in Setup.") }
            return
        }
        Guard.micMode.value = MicMode.from(ProfileRepository(this).micModeFlow.first())
        val audio = getSystemService(AudioManager::class.java)
        // The caller is heard through the loudspeaker, so the louder the call, the better. Restored afterwards.
        val volumeBefore = runCatching { audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) }.getOrNull()
        runCatching {
            if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
            }
        }
        var mode = Guard.micMode.value
        var mic = openMic(mode, audio) ?: openMic(MicMode.STANDARD, audio)
        if (mic == null) {
            Guard.ui.update { it.copy(problem = "The microphone is busy or unavailable.") }
            return
        }
        val buf = ByteArray(CHUNK)
        var level = GainControl()   // the caller's voice comes out of the loudspeaker, much quieter than the owner's
        var lastSound = SystemClock.elapsedRealtime()
        try {
            mic.rec.startRecording()
            withContext(Dispatchers.IO) {
                while (isActive) {
                    // The user can try another microphone path mid-call; the transcript carries on.
                    val wanted = Guard.micMode.value
                    if (wanted != mode) {
                        mic!!.close()
                        mode = wanted
                        mic = openMic(mode, audio) ?: openMic(MicMode.STANDARD, audio)
                        val m = mic
                        if (m == null) {
                            Guard.ui.update { it.copy(problem = "The microphone is busy or unavailable.") }
                            break
                        }
                        m.rec.startRecording()
                        level = GainControl()
                        lastSound = SystemClock.elapsedRealtime()
                    }
                    val rec = mic!!.rec
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // During a phone call Android feeds ordinary apps pure silence. Say so instead
                    // of showing a calm green meter that is listening to nothing.
                    val nowMs = SystemClock.elapsedRealtime()
                    val silencedByPolicy = rec.activeRecordingConfiguration?.isClientSilenced == true
                    if (!isDigitalSilence(buf, n)) lastSound = nowMs
                    val silenced = silencedByPolicy || nowMs - lastSound > 5_000
                    if (silenced != Guard.ui.value.micSilenced) Guard.ui.update { it.copy(micSilenced = silenced) }
                    level.process(buf, n)   // after the silence check, which must see the untouched samples
                    val event = stt.accept(buf, n)
                    if (event != null) withContext(confined) { session.onEvent(event) }
                }
            }
        } finally {
            mic?.close()
            if (volumeBefore != null) runCatching { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, volumeBefore, 0) }
        }
    }

    /** An open recorder with the phone's own voice processing switched off for it. */
    private class Mic(val rec: AudioRecord, private val effects: List<AudioEffect>) {
        fun close() {
            runCatching { rec.stop() }
            effects.forEach { runCatching { it.release() } }
            rec.release()
        }
    }

    private fun openMic(mode: MicMode, audio: AudioManager): Mic? {
        val rawSupported = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = when (mode) {
            MicMode.AUTO -> if (rawSupported) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            MicMode.STANDARD -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            MicMode.RAW -> MediaRecorder.AudioSource.UNPROCESSED
            MicMode.CAMERA -> MediaRecorder.AudioSource.CAMCORDER
            MicMode.PLAIN -> MediaRecorder.AudioSource.MIC
        }
        val min = AudioRecord.getMinBufferSize(SpeechEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val rec = runCatching {
            AudioRecord(source, SpeechEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
        }.getOrNull() ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        // Echo cancellation exists to remove what the loudspeaker plays, which here is the caller: the
        // one voice this app most needs. Noise suppression and the phone's own gain also hurt recognition.
        val id = rec.audioSessionId
        val effects = listOfNotNull(
            runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(id) else null }.getOrNull(),
            runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(id) else null }.getOrNull(),
            runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(id) else null }.getOrNull(),
        )
        effects.forEach { runCatching { it.enabled = false } }
        return Mic(rec, effects)
    }

    // ---- demo: a bundled recording through the same pipeline --------------------------------

    private suspend fun runRecording(asset: String, stt: SpeechEngine.Stream, session: GuardSession) {
        val pcm = assets.open(asset).use { readWavPcm(it) }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SpeechEngine.SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(CHUNK * 4)
            .build()
        val bytesPerMs = SpeechEngine.SAMPLE_RATE * 2 / 1000
        val started = SystemClock.elapsedRealtime()
        try {
            track.play()
            withContext(Dispatchers.IO) {
                var pos = 0
                while (isActive && pos < pcm.size) {
                    val n = minOf(CHUNK, pcm.size - pos)
                    track.write(pcm, pos, n)
                    val event = stt.accept(pcm.copyOfRange(pos, pos + n), n)
                    if (event != null) withContext(confined) { session.onEvent(event) }
                    pos += n
                    // Keep to real time even if the audio output is not pacing us (headless emulator).
                    val ahead = pos / bytesPerMs - (SystemClock.elapsedRealtime() - started)
                    if (ahead > 150) delay(ahead - 100)
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    // ---- alerts: the user is looking at the call screen, not at this app --------------------

    private suspend fun watchForAlerts() {
        Guard.ui.collect { ui ->
            val band = ui.score.band
            if (band.ordinal > lastAlertBand.ordinal && (band == Band.AMBER || band == Band.RED)) {
                lastAlertBand = band
                val (title, text) = if (band == Band.RED)
                    "Likely a scam. Hang up." to "Call back on the official number. Never share a code or install an app."
                else
                    "Be careful on this call" to "Do not share any code (OTP) or PIN. Do not install any app they ask for."
                val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle(title).setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVibrate(if (band == Band.RED) longArrayOf(0, 400, 150, 400, 150, 400) else longArrayOf(0, 250))
                    .setContentIntent(openApp(this))
                    .setAutoCancel(true)
                    .build()
                getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
            }
        }
    }

    private fun ongoingNotification(mode: Mode): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(if (mode == Mode.LIVE) "Scam Guard is listening to this call" else "Scam Guard demo is playing")
            .setContentText("Nothing is recorded. Tap to see the risk meter.")
            .setOngoing(true)
            .setContentIntent(openApp(this))
            .addAction(0, "Stop", PendingIntent.getService(this, 1, Intent(this, CallGuardService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
            .build()

    companion object {
        const val ACTION_LIVE = "com.scamguard.LIVE"
        const val ACTION_DEMO = "com.scamguard.DEMO"
        const val ACTION_STOP = "com.scamguard.STOP"
        const val EXTRA_ASSET = "asset"
        const val EXTRA_TITLE = "title"
        const val CHANNEL_ONGOING = "ongoing"
        const val CHANNEL_ALERT = "alert_v1"
        const val CHANNEL_CALLS = "calls_v1"
        private const val ONGOING_ID = 1
        private const val ALERT_ID = 2
        private const val CHUNK = 3200   // 100 ms of 16 kHz 16-bit mono

        fun startLive(c: Context) = ContextCompat.startForegroundService(c, Intent(c, CallGuardService::class.java).setAction(ACTION_LIVE))
        fun startDemo(c: Context, asset: String, title: String) = ContextCompat.startForegroundService(
            c, Intent(c, CallGuardService::class.java).setAction(ACTION_DEMO).putExtra(EXTRA_ASSET, asset).putExtra(EXTRA_TITLE, title),
        )
        fun stop(c: Context) { c.startService(Intent(c, CallGuardService::class.java).setAction(ACTION_STOP)) }

        fun openApp(c: Context, startLive: Boolean = false): PendingIntent = PendingIntent.getActivity(
            c, if (startLive) 11 else 10,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN, if (startLive) MainActivity.OPEN_START_LIVE else MainActivity.OPEN_LIVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun createChannels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Listening status", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Scam warnings", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_CALLS, "Unknown caller check", NotificationManager.IMPORTANCE_HIGH))
        }

        /** True if every sample is (near) zero: what Android hands an app it has muted. */
        fun isDigitalSilence(buf: ByteArray, n: Int): Boolean {
            var i = 0
            while (i + 1 < n) {
                val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                if (s > 3 || s < -3) return false
                i += 2
            }
            return true
        }

        /** Minimal WAV reader: accepts exactly what the pipeline needs (16 kHz, mono, 16-bit PCM). */
        fun readWavPcm(input: InputStream): ByteArray {
            val all = input.readBytes()
            fun le32(o: Int) = (all[o].toInt() and 0xff) or ((all[o + 1].toInt() and 0xff) shl 8) or
                ((all[o + 2].toInt() and 0xff) shl 16) or ((all[o + 3].toInt() and 0xff) shl 24)
            fun le16(o: Int) = (all[o].toInt() and 0xff) or ((all[o + 1].toInt() and 0xff) shl 8)
            fun tag(o: Int) = String(all, o, 4, Charsets.US_ASCII)
            require(all.size > 44 && tag(0) == "RIFF" && tag(8) == "WAVE") { "not a WAV file" }
            var pos = 12
            var formatOk = false
            while (pos + 8 <= all.size) {
                val id = tag(pos)
                val size = le32(pos + 4)
                val body = pos + 8
                if (id == "fmt ") {
                    formatOk = le16(body) == 1 && le16(body + 2) == 1 && le32(body + 4) == SpeechEngine.SAMPLE_RATE && le16(body + 14) == 16
                } else if (id == "data") {
                    require(formatOk) { "recording must be 16 kHz mono 16-bit PCM" }
                    return all.copyOfRange(body, minOf(all.size, body + size))
                }
                pos = body + size + (size and 1)
            }
            throw IllegalArgumentException("WAV has no audio data")
        }
    }
}
