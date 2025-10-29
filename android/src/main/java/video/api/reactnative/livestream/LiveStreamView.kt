package video.api.reactnative.livestream

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
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
    override fun onConnectionSuccess() = onConnectionSuccess?.invoke() ?: Unit
    override fun onConnectionFailed(reason: String) = onConnectionFailed?.invoke(reason) ?: Unit
    override fun onDisconnect() = onDisconnected?.invoke() ?: Unit
  }

  private val previewHost: ApiVideoView
  private val liveStream: ApiVideoLiveStream

  init {
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
            Log.e(TAG, "Asking rationale for missing permissions: ${missingPermissions.joinToString(", ")}")
            onPermissionsRationale?.invoke(missingPermissions)
          },
          onAtLeastOnePermissionDenied = { missingPermissions ->
            Log.e(TAG, "Missing permissions: ${missingPermissions.joinToString(", ")}")
            onPermissionsDenied?.invoke(missingPermissions)
          }
        )
      }
    )
  }

  // ——— Public API passthrough ———

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

  // ——— Lifecycle ———

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    // Try immediately and again on layout if needed
    startPreviewWhenReady()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    stopPreviewInternal()
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    if (hasWindowFocus) {
      // Returning from settings/onboarding often triggers this first
      startPreviewWhenReady()
    }
  }

  override fun onVisibilityAggregated(isVisible: Boolean) {
    super.onVisibilityAggregated(isVisible)
    if (isVisible) startPreviewWhenReady()
  }

  /**
   * Only start preview if camera permission is granted and the view is laid out.
   * Re-check permissions using ContextCompat to avoid stale caches.
   */
  private fun startPreviewWhenReady() {
    if (isClosed) {
      Log.w(TAG, "Skipping preview start: view released")
      return
    }
    if (!hasPermission(Manifest.permission.CAMERA)) {
      Log.d(TAG, "Skipping preview start: camera permission not granted")
      return
    }
    if (previewStartRequested) {
      Log.d(TAG, "Preview start already requested")
      return
    }
    previewStartRequested = true

    val start = {
      runOnUiThread {
        try {
          Log.d(TAG, "Starting camera preview")
          liveStream.startPreview()
          Log.d(TAG, "Camera preview started")
        } catch (e: Exception) {
          // Allow a later retry
          previewStartRequested = false
          Log.e(TAG, "Failed to start preview", e)
        }
      }
    }

    if (previewHost.width == 0 || previewHost.height == 0) {
      Log.d(TAG, "Preview host not laid out yet. Deferring start until layout")
      previewHost.doOnLayout { previewHost.post { start() } } // post one frame after layout
    } else {
      // Post to next frame so SurfaceProvider is ready
      previewHost.post { start() }
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
      require(hasPermission(Manifest.permission.CAMERA)) {
        "Missing permissions Manifest.permission.CAMERA"
      }
      require(hasPermission(Manifest.permission.RECORD_AUDIO)) {
        "Missing permissions Manifest.permission.RECORD_AUDIO"
      }

      startPreviewWhenReady()

      if (orientationManager.orientationHasChanged) {
        Log.d(TAG, "Orientation changed, reapplying video config")
        liveStream.videoConfig = liveStream.videoConfig
      }

      Log.d(TAG, "Calling startStreaming streamKey=${streamKey.take(8)}..., url=$url")
      if (url != null) liveStream.startStreaming(streamKey, url)
      else liveStream.startStreaming(streamKey)

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
  override fun onHostResume() {
    if (isClosed) {
      Log.w(TAG, "onHostResume ignored: view released")
      return
    }
    // Re-check permissions on resume. If mic granted now, reapply audio config.
    if (hasPermission(Manifest.permission.CAMERA)) startPreviewWhenReady()
    if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
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
    (context as ThemedReactContext).removeLifecycleEventListener(this)
    close()
  }

  // ——— Helpers ———
  private fun hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
