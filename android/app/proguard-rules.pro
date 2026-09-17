# WebRTC JNI entry points must remain reachable when release shrinking is enabled.
-keep class org.webrtc.** { *; }
