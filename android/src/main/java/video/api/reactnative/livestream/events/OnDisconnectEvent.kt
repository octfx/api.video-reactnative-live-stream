package video.api.reactnative.livestream.events

import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.Event
import video.api.reactnative.livestream.ViewProps

class OnDisconnectEvent : Event<OnDisconnectEvent> {
  @Deprecated("Use constructor with surfaceId")
  constructor(tag: Int) : this(-1, tag)

  constructor(surfaceId: Int, tag: Int) : super(surfaceId, tag)

  override fun getEventName() = ViewProps.Events.DISCONNECTED.eventName

  override fun getEventData(): WritableMap? = null
}
