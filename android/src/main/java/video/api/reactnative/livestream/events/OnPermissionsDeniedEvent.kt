package video.api.reactnative.livestream.events

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.Event
import video.api.reactnative.livestream.ViewProps

class OnPermissionsDeniedEvent : Event<OnPermissionsDeniedEvent> {
  private val permissions: List<String>

  @Deprecated("Use constructor with surfaceId")
  constructor(tag: Int, permissions: List<String>) : this(-1, tag, permissions)

  constructor(surfaceId: Int, tag: Int, permissions: List<String>) : super(surfaceId, tag) {
    this.permissions = permissions
  }

  override fun getEventName() = ViewProps.Events.PERMISSIONS_DENIED.eventName

  override fun getEventData(): WritableMap {
    return Arguments.createMap().apply {
      putArray("permissions", Arguments.fromList(permissions))
    }
  }
}
