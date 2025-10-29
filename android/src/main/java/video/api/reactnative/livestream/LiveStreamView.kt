package video.api.reactnative.livestream

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.ScaleGestureDetector
import androidx.constraintlayout.widget.ConstraintLayout
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.UiThreadUtil.runOnUiThread
import com.facebook.react.uimanager.ThemedReactContext
import video.api.livestream.ApiVideoLiveStream
import video.api.livestream.enums.CameraFacingDirection
import video.api.livestream.interfaces.IConnectionListener
import video.api.livestream.models.AudioConfig
import video.api.livestream.models.VideoConfig
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
  private val liveStream: ApiVideoLiveStream
  private val permissionsManager = SerialPermissionsManager(
    PermissionsManager((context as ThemedReactContext).reactApplicationContext)
  )

  private val orientationManager = OrientationManager(context)
  private var isClosed = false
  private var isPreviewRunning = false

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
      onConnectionSuccess?.let { it() }
    }

    override fun onConnectionFailed(reason: String) {
      onConnectionFailed?.let { it(reason) }
    }

    override fun onDisconnect() {
      onDisconnected?.let { it() }
    }
  }

  init {
    inflate(context, R.layout.react_native_livestream, this)
    liveStream = ApiVideoLiveStream(
      context = context,
      connectionListener = connectionListener,
      apiVideoView = findViewById(R.id.apivideo_view),
      permissionRequester = { permissions, onGranted ->
        permissionsManager.requestPermissions(
          permissions,
          onAllGranted = {
            runOnUiThread { onGranted() }
          },
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
            onPermissionsRationale?.let { it(missingPermissions) }
          },
          onAtLeastOnePermissionDenied = { missingPermissions ->
            val permissionsStrings = missingPermissions.joinToString(", ")
            Log.e(TAG, "Missing permissions: $permissionsStrings")
            onPermissionsDenied?.let { it(missingPermissions) }
          })
      }
    )
  }

  var videoBitrate: Int
    get() = liveStream.videoBitrate
    set(value) {
      liveStream.videoBitrate = value
    }

  var videoConfig: VideoConfig?
    get() = liveStream.videoConfig
    set(value) {
      /**
       * Camera permission is required when `startPreview` is called internally. The permission
       * request goes through the `permissionRequester` callback.
       * Note: Setting videoConfig may trigger preview start in the underlying SDK, which is
       * separate from our isPreviewRunning state tracking.
       */
      Log.d(TAG, "Setting videoConfig - current preview state: running=$isPreviewRunning")
      liveStream.videoConfig = value
    }


  var audioConfig: AudioConfig?
    get() = liveStream.audioConfig
    set(value) {
      /**
       * Record audio permission is required when `configure` is called internally. The permission
       * request goes through the `permissionRequester` callback.
       */
      liveStream.audioConfig = value
    }

  val isStreaming: Boolean
    get() = liveStream.isStreaming

  var camera: CameraFacingDirection
    get() = liveStream.cameraPosition
    set(value) {
      liveStream.cameraPosition = value
    }

  var isMuted: Boolean
    get() = liveStream.isMuted
    set(value) {
      liveStream.isMuted = value
    }

  var zoomRatio: Float
    get() = liveStream.zoomRatio
    set(value) {
      liveStream.zoomRatio = value
    }

  var enablePinchedZoom: Boolean = false
    @SuppressLint("ClickableViewAccessibility")
    set(value) {
      if (value) {
        this.setOnTouchListener { _, event ->
          pinchGesture.onTouchEvent(event)
        }
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
            savedZoomRatio + ((detector.scaleFactor - 1))
          }
          return super.onScale(detector)
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
          savedZoomRatio = zoomRatio
          return super.onScaleBegin(detector)
        }
      })
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    ensurePreviewStarted()
  }

  private fun ensurePreviewStarted() {
    if (isClosed) {
      Log.w(TAG, "Skipping preview start: view already released")
      return
    }
    if (isPreviewRunning) {
      Log.d(TAG, "Preview already running, skipping start")
      return
    }
    try {
      Log.d(TAG, "Starting camera preview")
      liveStream.startPreview()
      isPreviewRunning = true
      Log.d(TAG, "Camera preview started successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start preview", e)
      isPreviewRunning = false
      throw e
    }
  }

  private fun stopPreviewInternal() {
    if (!isPreviewRunning) {
      Log.d(TAG, "Preview not running, skipping stop")
      return
    }
    try {
      Log.d(TAG, "Stopping camera preview")
      liveStream.stopPreview()
      isPreviewRunning = false
      Log.d(TAG, "Camera preview stopped successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to stop preview", e)
      // Still mark as not running even if stop failed
      isPreviewRunning = false
    }
  }

  fun startStreaming(requestId: Int, streamKey: String, url: String?) {
    if (isClosed) {
      val message = "LiveStreamView already released"
      Log.w(TAG, "startStreaming ignored: $message")
      onStartStreaming?.let { it(requestId, false, message) }
      return
    }
    try {
      Log.d(TAG, "Starting streaming - preview state: running=$isPreviewRunning")

      require(permissionsManager.hasPermission(Manifest.permission.CAMERA)) { "Missing permissions Manifest.permission.CAMERA" }
      require(permissionsManager.hasPermission(Manifest.permission.RECORD_AUDIO)) { "Missing permissions Manifest.permission.RECORD_AUDIO" }

      // Ensure preview is started (will skip if already running)
      ensurePreviewStarted()

      /**
       * Workaround to reapply video config in case orientation has changed.
       * This happens because `configChanges` may be disabled in the AndroidManifest.xml of a RN
       * application.
       */
      if (orientationManager.orientationHasChanged) {
        Log.d(TAG, "Orientation changed, reapplying video config")
        liveStream.videoConfig = liveStream.videoConfig
      }

      Log.d(TAG, "Calling liveStream.startStreaming with streamKey=${streamKey.take(8)}..., url=$url")
      url?.let { liveStream.startStreaming(streamKey, it) }
        ?: liveStream.startStreaming(streamKey)

      Log.d(TAG, "Streaming started successfully")
      onStartStreaming?.let { it(requestId, true, null) }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start streaming - preview state: running=$isPreviewRunning", e)
      onStartStreaming?.let { it(requestId, false, e.message) }
    }
  }

  fun stopStreaming() {
    if (isClosed) {
      Log.w(TAG, "stopStreaming ignored: LiveStreamView already released")
      return
    }
    Log.d(TAG, "Stopping streaming - preview state: running=$isPreviewRunning")
    liveStream.stopStreaming()
    Log.d(TAG, "Streaming stopped successfully")
  }

  override fun close() {
    if (isClosed) {
      Log.w(TAG, "close ignored: LiveStreamView already released")
      return
    }
    Log.d(TAG, "Releasing LiveStreamView resources")
    isClosed = true
    isPreviewRunning = false
    orientationManager.close()
    liveStream.release()
    Log.d(TAG, "LiveStreamView resources released")
  }

  companion object {
    private const val TAG = "RNLiveStreamView"
  }

  /**
   * If you request a permission here, it will loop indefinitely between [onHostPause] and
   * [onHostResume].
   */
  override fun onHostResume() {
    if (isClosed) {
      Log.w(TAG, "onHostResume ignored: view already released")
      return
    }
    /**
     * Only start preview if the app has the required permissions.
     */
    if (permissionsManager.hasPermission(Manifest.permission.CAMERA)) {
      ensurePreviewStarted()
    }
    /**
     * Workaround to reapply audio config in case it was not applied when the app started (due to
     * missing RECORD_AUDIO permissions).
     */
    if (permissionsManager.hasPermission(Manifest.permission.RECORD_AUDIO)) {
      liveStream.audioConfig = liveStream.audioConfig
    }
  }

  override fun onHostPause() {
    if (isClosed) {
      Log.w(TAG, "onHostPause ignored: view already released")
      return
    }
    liveStream.stopStreaming()
    stopPreviewInternal()
  }

  override fun onHostDestroy() {
    close()
  }
}
