package video.api.reactnative.livestream.events

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.Event
import video.api.reactnative.livestream.ViewProps

class OnConnectionFailedEvent : Event<OnConnectionFailedEvent> {
  private val reason: String?

  @Deprecated("Use constructor with surfaceId")
  constructor(tag: Int, reason: String?) : this(-1, tag, reason)

  constructor(surfaceId: Int, tag: Int, reason: String?) : super(surfaceId, tag) {
    this.reason = reason
  }

  override fun getEventName() = ViewProps.Events.CONNECTION_FAILED.eventName

  override fun getEventData(): WritableMap {
    return Arguments.createMap().apply {
      putString("code", reason)
    }
  }
}
