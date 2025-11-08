import ApiVideoLiveStream
import CoreGraphics
import Foundation
import React

@objc(RNLiveStreamViewManager)
class RNLiveStreamViewManager: RCTViewManager {
  override static func requiresMainQueueSetup() -> Bool { true }

  override func view() -> RNLiveStreamViewImpl { RNLiveStreamViewImpl() }

  @objc(startStreaming:withRequestId:withStreamKey:withUrl:)
  func startStreaming(_ reactTag: NSNumber, withRequestId requestId: NSNumber, streamKey: String, url: String?) {
    guard let uiManager = bridge?.uiManager else {
      NSLog("[RNLiveStreamViewManager] bridge/uiManager is nil")
      return
    }

    uiManager.addUIBlock { uiManager, _ in
      guard let view = uiManager?.view(forReactTag: reactTag) as? RNLiveStreamViewImpl else {
        NSLog("[RNLiveStreamViewManager] view for tag \(reactTag) not found or wrong type")
        return
      }
      view.startStreaming(requestId: requestId.intValue, streamKey: streamKey, url: url)
    }
  }

  @objc(stopStreaming:)
  func stopStreaming(_ reactTag: NSNumber) {
    guard let uiManager = bridge?.uiManager else {
      NSLog("[RNLiveStreamViewManager] bridge/uiManager is nil")
      return
    }

    uiManager.addUIBlock { uiManager, _ in
      guard let view = uiManager?.view(forReactTag: reactTag) as? RNLiveStreamViewImpl else {
        NSLog("[RNLiveStreamViewManager] view for tag \(reactTag) not found")
        return
      }
      view.stopStreaming()
    }
  }

  @objc(setZoomRatioCommand:withZoomRatio:)
  func setZoomRatioCommand(_ reactTag: NSNumber, zoomRatio: NSNumber) {
    guard let uiManager = bridge?.uiManager else {
      NSLog("[RNLiveStreamViewManager] bridge/uiManager is nil")
      return
    }

    uiManager.addUIBlock { uiManager, _ in
      guard let view = uiManager?.view(forReactTag: reactTag) as? RNLiveStreamViewImpl else {
        NSLog("[RNLiveStreamViewManager] view for tag \(reactTag) not found")
        return
      }
      view.zoomRatio = zoomRatio.floatValue
    }
  }
}
