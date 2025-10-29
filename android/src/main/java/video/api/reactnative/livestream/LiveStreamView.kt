package video.api.reactnative.livestream

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.doOnLayout
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.UiThreadUtil.runOnUiThread
import com.facebook.react.uimanager.ThemedReactContext
import video.api.livestream.ApiVideoLiveStream
import video.api.livestream.enums.CameraFacingDirection
import video.api.livestream.interfaces.IConnectionListener
import video.api.livestream.models.AudioConfig
import video.api.livestream.models.VideoConfig
import video.api.livestream.views.ApiVideoView
import video.api.reactnative.livestream.utils.OrientationManager
import video.api.reactnative.livestream.utils.permissions.PermissionsManager
import video.api.reactnative.livestream.utils.permissions.SerialPermissionsManager
import video.api.reactnative.livestream.utils.showDialog
import java.io.Closeable

@SuppressLint("MissingPermission")
class LiveStreamView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyle: Int = 0
) : ConstraintLayout(context, attrs, defStyle),
  Closeable, LifecycleEventListener {

  private val permissionsManager = SerialPermissionsManager(
    PermissionsManager((context as ThemedReactContext).reactApplicationContext)
  )

  private val orientationManager = OrientationManager(context)
  private var isClosed = false

  // Avoid duplicate start requests while we wait for layout
  @Volatile
  private var previewStartRequested = false

  // Connection listeners
  var onConnectionSuccess: (() -> Unit)? = null
  var onConnectionFailed: ((reason: String?) -> Unit)? = null
  var onDisconnected: (() -> Unit)? = null

  // Permission listeners
  var onPermissionsDenied: ((List<String>) -> Unit)? = null
  var onPermissionsRationale: ((List<String>) -> Unit)? = null

  // Internal usage only
  var onStartStreaming: ((requestId: Int, result: Boolean, error: String?) -> Unit)? = null

  private val connectionListener = object : IConnectionListener {
    override fun onConnectionSuccess() {
      onConnectionSuccess?.invoke()
    }
    override fun onConnectionFailed(reason: String) {
      onConnectionFailed?.invoke(reason)
    }
    override fun onDisconnect() {
      onDisconnected?.invoke()
    }
  }

  private val previewHost: ApiVideoView
  private val liveStream: ApiVideoLiveStream

  init {
    // Register for RN lifecycle callbacks
    (context as ThemedReactContext).addLifecycleEventListener(this)

    inflate(context, R.layout.react_native_livestream, this)
    previewHost = findViewById<ApiVideoView>(R.id.apivideo_view)

    liveStream = ApiVideoLiveStream(
      context = context,
      connectionListener = connectionListener,
      apiVideoView = previewHost,
      permissionRequester = { permissions, onGranted ->
        permissionsManager.requestPermissions(
          permissions,
          onAllGranted = { runOnUiThread { onGranted() } },
          onShowPermissionRationale = { missingPermissions, onRequiredPermissionLastTime ->
            runOnUiThread {
              when {
                missingPermissions.size > 1 -> {
                  context.showDialog(
                    R.string.permission_required,
                    R.string.camera_and_record_audio_permission_required_message,
                    android.R.string.ok,
                    onPositiveButtonClick = { onRequiredPermissionLastTime() }
                  )
                }
                missingPermissions.contains(Manifest.permission.CAMERA) -> {
                  context.showDialog(
                    R.string.permission_required,
                    R.string.camera_permission_required_message,
                    android.R.string.ok,
                    onPositiveButtonClick = { onRequiredPermissionLastTime() }
                  )
                }
                missingPermissions.contains(Manifest.permission.RECORD_AUDIO) -> {
                  context.showDialog(
                    R.string.permission_required,
                    R.string.record_audio_permission_required_message,
                    android.R.string.ok,
                    onPositiveButtonClick = { onRequiredPermissionLastTime() }
                  )
                }
              }
            }
            val permissionsStrings = missingPermissions.joinToString(", ")
            Log.e(TAG, "Asking rationale for missing permissions: $permissionsStrings")
            onPermissionsRationale?.invoke(missingPermissions)
          },
          onAtLeastOnePermissionDenied = { missingPermissions ->
            val permissionsStrings = missingPermissions.joinToString(", ")
            Log.e(TAG, "Missing permissions: $permissionsStrings")
            onPermissionsDenied?.invoke(missingPermissions)
          }
        )
      }
    )
  }

  var videoBitrate: Int
    get() = liveStream.videoBitrate
    set(value) { liveStream.videoBitrate = value }

  var videoConfig: VideoConfig?
    get() = liveStream.videoConfig
    set(value) {
      Log.d(TAG, "Setting videoConfig")
      liveStream.videoConfig = value
    }

  var audioConfig: AudioConfig?
    get() = liveStream.audioConfig
    set(value) { liveStream.audioConfig = value }

  val isStreaming: Boolean
    get() = liveStream.isStreaming

  var camera: CameraFacingDirection
    get() = liveStream.cameraPosition
    set(value) { liveStream.cameraPosition = value }

  var isMuted: Boolean
    get() = liveStream.isMuted
    set(value) { liveStream.isMuted = value }

  var zoomRatio: Float
    get() = liveStream.zoomRatio
    set(value) { liveStream.zoomRatio = value }

  var enablePinchedZoom: Boolean = false
    @SuppressLint("ClickableViewAccessibility")
    set(value) {
      if (value) {
        this.setOnTouchListener { _, event -> pinchGesture.onTouchEvent(event) }
      } else {
        this.setOnTouchListener(null)
      }
      field = value
    }

  private val pinchGesture: ScaleGestureDetector by lazy {
    ScaleGestureDetector(
      context,
      object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var savedZoomRatio: Float = 1f
        override fun onScale(detector: ScaleGestureDetector): Boolean {
          zoomRatio = if (detector.scaleFactor < 1) {
            savedZoomRatio * detector.scaleFactor
          } else {
            savedZoomRatio + (detector.scaleFactor - 1)
          }
          return true
        }
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
          savedZoomRatio = zoomRatio
          return true
        }
      }
    )
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    startPreviewWhenReady()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    stopPreviewInternal()
  }

  private fun startPreviewWhenReady() {
    if (isClosed) {
      Log.w(TAG, "Skipping preview start: view released")
      return
    }
    if (!permissionsManager.hasPermission(Manifest.permission.CAMERA)) {
      Log.d(TAG, "Skipping preview start: camera permission not granted")
      return
    }
    if (previewStartRequested) {
      Log.d(TAG, "Preview start already requested")
      return
    }
    previewStartRequested = true

    // Ensure the preview host has a surface and size before starting.
    if (previewHost.width == 0 || previewHost.height == 0) {
      Log.d(TAG, "Preview host not laid out yet. Deferring start until layout")
      previewHost.doOnLayout { startPreviewNow() }
    } else {
      startPreviewNow()
    }
  }

  private fun startPreviewNow() {
    runOnUiThread {
      try {
        Log.d(TAG, "Starting camera preview")
        liveStream.startPreview()
        Log.d(TAG, "Camera preview started")
      } catch (e: Exception) {
        // Allow a later retry by clearing the request flag
        previewStartRequested = false
        Log.e(TAG, "Failed to start preview", e)
      }
    }
  }

  private fun stopPreviewInternal() {
    previewStartRequested = false
    try {
      Log.d(TAG, "Stopping camera preview")
      liveStream.stopPreview()
      Log.d(TAG, "Camera preview stopped")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to stop preview", e)
    }
  }

  fun startStreaming(requestId: Int, streamKey: String, url: String?) {
    if (isClosed) {
      val message = "LiveStreamView already released"
      Log.w(TAG, "startStreaming ignored: $message")
      onStartStreaming?.invoke(requestId, false, message)
      return
    }
    try {
      require(permissionsManager.hasPermission(Manifest.permission.CAMERA)) {
        "Missing permissions Manifest.permission.CAMERA"
      }
      require(permissionsManager.hasPermission(Manifest.permission.RECORD_AUDIO)) {
        "Missing permissions Manifest.permission.RECORD_AUDIO"
      }

      // Make sure preview is up
      startPreviewWhenReady()

      // Reapply video config on orientation change
      if (orientationManager.orientationHasChanged) {
        Log.d(TAG, "Orientation changed, reapplying video config")
        liveStream.videoConfig = liveStream.videoConfig
      }

      Log.d(TAG, "Calling startStreaming streamKey=${streamKey.take(8)}..., url=$url")
      if (url != null) liveStream.startStreaming(streamKey, url) else liveStream.startStreaming(streamKey)

      Log.d(TAG, "Streaming started")
      onStartStreaming?.invoke(requestId, true, null)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start streaming", e)
      onStartStreaming?.invoke(requestId, false, e.message)
    }
  }

  fun stopStreaming() {
    if (isClosed) {
      Log.w(TAG, "stopStreaming ignored: view released")
      return
    }
    Log.d(TAG, "Stopping streaming")
    liveStream.stopStreaming()
    Log.d(TAG, "Streaming stopped")
  }

  override fun close() {
    if (isClosed) {
      Log.w(TAG, "close ignored: view released")
      return
    }
    Log.d(TAG, "Releasing LiveStreamView resources")
    isClosed = true
    previewStartRequested = false
    orientationManager.close()
    try { liveStream.release() } catch (e: Exception) {
      Log.e(TAG, "LiveStream release failed", e)
    }
    Log.d(TAG, "LiveStreamView resources released")
  }

  companion object {
    private const val TAG = "RNLiveStreamView"
  }

  // React Native lifecycle

  /**
   * Do not trigger permission requests here. Only start if already granted.
   */
  override fun onHostResume() {
    if (isClosed) {
      Log.w(TAG, "onHostResume ignored: view released")
      return
    }
    if (permissionsManager.hasPermission(Manifest.permission.CAMERA)) {
      startPreviewWhenReady()
    }
    if (permissionsManager.hasPermission(Manifest.permission.RECORD_AUDIO)) {
      // Ensure audio config is applied when app resumes
      liveStream.audioConfig = liveStream.audioConfig
    }
  }

  override fun onHostPause() {
    if (isClosed) {
      Log.w(TAG, "onHostPause ignored: view released")
      return
    }
    liveStream.stopStreaming()
    stopPreviewInternal()
  }

  override fun onHostDestroy() {
    // Unregister lifecycle listener and release
    (context as ThemedReactContext).removeLifecycleEventListener(this)
    close()
  }
}
