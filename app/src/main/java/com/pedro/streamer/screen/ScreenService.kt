package com.pedro.streamer.screen

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.BitrateAdapter
import com.pedro.streamer.R
import com.pedro.streamer.utils.PathUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class ScreenService: AccessibilityService(), ConnectChecker {

  companion object {
    private const val TAG = "ScreenService"
    private const val CHANNEL_ID = "DisplayStreamChannel"
    const val NOTIFY_ID = 123456
    var INSTANCE: ScreenService? = null
  }

  private var notificationManager: NotificationManager? = null
  private lateinit var genericStream: GenericStream
  private var mediaProjection: MediaProjection? = null
  private val mediaProjectionManager: MediaProjectionManager by lazy {
    applicationContext.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
  }
  private var callback: ConnectChecker? = null
  private val width = 2560
  private val height = 1440
  private val vBitrate = 16000 * 1000
  private var rotation = 0
  private val sampleRate = 48000
  private val isStereo = true
  private val aBitrate = 192 * 1000
  private var recordPath = ""
  private var selectedAudioSource: Int = R.id.audio_source_microphone
  private val source = MediaRecorder.AudioSource.MIC
  private var bitrateAdapter: BitrateAdapter? = null

  private var prepared = false

  override fun onCreate() {
    super.onCreate()
    INSTANCE = this
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(CHANNEL_ID, CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH)
      notificationManager?.createNotificationChannel(channel)
    }
    genericStream = GenericStream(baseContext, this, NoVideoSource(), MicrophoneSource(audioSource = source)).apply {
      getGlInterface().autoHandleOrientation = true
      getGlInterface().setForceRender(true, 60)
      setVideoCodec(VideoCodec.H265)
    }
    prepared = try {
      genericStream.prepareVideo(width, height, vBitrate, fps = 60, iFrameInterval = 2, rotation = rotation) &&
        genericStream.prepareAudio(sampleRate, isStereo, aBitrate, echoCanceler = true, noiseSuppressor = true)
    } catch (e: IllegalArgumentException) {
      false
    }
  }

  private fun keepAliveTrick() {
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.notification_icon)
      .setSilent(true)
      .setOngoing(false)
      .build()
    startForeground(1, notification)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

  override fun onInterrupt() {}

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    INSTANCE = this
    keepAliveTrick()
    return START_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
    genericStream.release()
    mediaProjection?.stop()
    INSTANCE = null
  }

  fun sendIntent(): Intent {
    return mediaProjectionManager.createScreenCaptureIntent()
  }

  fun isStreaming(): Boolean = genericStream.isStreaming

  fun isRecording(): Boolean = genericStream.isRecording

  fun stopStream() {
    if (genericStream.isStreaming) {
      genericStream.stopStream()
      notificationManager?.cancel(NOTIFY_ID)
    }
  }

  fun checkIfPrepared(): Boolean {
    return prepared
  }

  fun prepareStream(resultCode: Int, data: Intent): Boolean {
    keepAliveTrick()
    stopStream()
    if (genericStream.isRecording) genericStream.stopRecord()
    val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
    this.mediaProjection = mediaProjection
    val screenSource = ScreenSource(applicationContext, mediaProjection)
    return try {
      genericStream.getGlInterface().setForceRender(true, 60)
      genericStream.changeVideoSource(screenSource)
      toggleAudioSource(selectedAudioSource)
      true
    } catch (ignored: IllegalArgumentException) {
      false
    }
  }

  fun getCurrentAudioSource(): Int = selectedAudioSource

  fun toggleAudioSource(itemId: Int) {
    when (itemId) {
      R.id.audio_source_microphone -> {
        selectedAudioSource = R.id.audio_source_microphone
        if (genericStream.audioSource is MicrophoneSource) return
        genericStream.changeAudioSource(MicrophoneSource(audioSource = source))
      }
      R.id.audio_source_internal -> {
        selectedAudioSource = R.id.audio_source_internal
        if (genericStream.audioSource is InternalAudioSource) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          mediaProjection?.let { genericStream.changeAudioSource(InternalAudioSource(it)) }
        } else {
          throw IllegalArgumentException("You need min API 29+")
        }
      }
      R.id.audio_source_mix -> {
        selectedAudioSource = R.id.audio_source_mix
        if (genericStream.audioSource is MixAudioSource) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          mediaProjection?.let {
            val mixSource = MixAudioSource(it, microphoneAudioSource = source).apply {
              internalVolume = 0.55f
              microphoneVolume = 1.0f
            }
            genericStream.changeAudioSource(mixSource)
          }
        } else {
          throw IllegalArgumentException("You need min API 29+")
        }
      }
    }
  }

  fun toggleRecord(state: (RecordController.Status) -> Unit) {
    if (!genericStream.isRecording) {
      val folder = PathUtils.getRecordPath()
      if (!folder.exists()) folder.mkdir()
      val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
      recordPath = "${folder.absolutePath}/${sdf.format(Date())}.mp4"
      genericStream.startRecord(recordPath) { status ->
        if (status == RecordController.Status.RECORDING) {
          state(RecordController.Status.RECORDING)
        }
      }
      state(RecordController.Status.STARTED)
    } else {
      genericStream.stopRecord()
      state(RecordController.Status.STOPPED)
      PathUtils.updateGallery(this, recordPath)
    }
  }

  fun startStream(endpoint: String) {
    if (!genericStream.isStreaming) genericStream.startStream(endpoint)
  }

  override fun onConnectionStarted(url: String) {
    callback?.onConnectionStarted(url)
  }

  override fun onConnectionSuccess() {
    bitrateAdapter = BitrateAdapter { bitrate ->
      genericStream.setVideoBitrateOnFly(bitrate)
    }.apply {
      setMaxBitrate(vBitrate + aBitrate)
    }
    callback?.onConnectionSuccess()
  }

  override fun onNewBitrate(bitrate: Long) {
    bitrateAdapter?.adaptBitrate(bitrate, genericStream.getStreamClient().hasCongestion())
    callback?.onNewBitrate(bitrate)
  }

  override fun onConnectionFailed(reason: String) {
    callback?.onConnectionFailed(reason)
  }

  override fun onDisconnect() {
    callback?.onDisconnect()
  }

  override fun onAuthError() {
    callback?.onAuthError()
  }

  override fun onAuthSuccess() {
    callback?.onAuthSuccess()
  }

  fun setCallback(callback: ConnectChecker?) {
    this.callback = callback
  }
}
