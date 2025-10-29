package video.api.reactnative.livestream.events

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.Event
import video.api.reactnative.livestream.ViewProps

class OnStartStreamingEvent : Event<OnStartStreamingEvent> {
  private val requestId: Int
  private val result: Boolean
  private val error: String?

  @Deprecated("Use constructor with surfaceId")
  constructor(
    tag: Int,
    requestId: Int,
    result: Boolean,
    error: String? = null
  ) : this(-1, tag, requestId, result, error)

  constructor(
    surfaceId: Int,
    tag: Int,
    requestId: Int,
    result: Boolean,
    error: String? = null
  ) : super(surfaceId, tag) {
    this.requestId = requestId
    this.result = result
    this.error = error
  }

  override fun getEventName() = ViewProps.Events.START_STREAMING.eventName

  override fun getEventData(): WritableMap {
    return Arguments.createMap().apply {
      putInt("requestId", requestId)
      putBoolean("result", result)
      error?.let { putString("error", it) }
    }
  }
}
