package video.api.reactnative.livestream.utils.permissions

import java.util.concurrent.Executors

/**
 * SerialPermissionsManager is a wrapper around PermissionsManager that serializes permission requests.
 */
class SerialPermissionsManager(
  private val permissionsManager: PermissionsManager
) {
  private val executor = Executors.newSingleThreadExecutor()
  private val permissionRequests = mutableListOf<Runnable>()

  fun hasPermission(permission: String): Boolean {
    return permissionsManager.hasPermission(permission)
  }

  private fun processNextRequest(completedRequest: Runnable) {
    val nextRequest: Runnable?
    synchronized(this) {
      permissionRequests.remove(completedRequest)
      nextRequest = permissionRequests.firstOrNull()
    }

    nextRequest?.let { executor.execute(it) }
  }

  fun requestPermissions(
    permissions: List<String>,
    onAllGranted: () -> Unit,
    onShowPermissionRationale: (List<String>, () -> Unit) -> Unit,
    onAtLeastOnePermissionDenied: (List<String>) -> Unit
  ) {
    val request = object : Runnable {
      override fun run() {
        permissionsManager.requestPermissions(
          permissions,
          {
            onAllGranted()
            processNextRequest(this)
          },
          onShowPermissionRationale,
          { permissions ->
            onAtLeastOnePermissionDenied(permissions)
            processNextRequest(this)
          }
        )
      }
    }
    synchronized(this) {
      permissionRequests.add(request)
      if (permissionRequests.size == 1) {
        executor.execute(request)
      }
    }
  }

  fun requestPermission(
    permission: String,
    onGranted: () -> Unit,
    onShowPermissionRationale: (() -> Unit) -> Unit,
    onDenied: () -> Unit
  ) {
    val request = object : Runnable {
      override fun run() {
        permissionsManager.requestPermission(
          permission,
          {
            onGranted()
            processNextRequest(this)
          },
          onShowPermissionRationale,
          {
            onDenied()
            processNextRequest(this)
          }
        )
      }
    }
    synchronized(this) {
      permissionRequests.add(request)
      if (permissionRequests.size == 1) {
        executor.execute(request)
      }
    }
  }
}
