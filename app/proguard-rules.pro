# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# HiveMQ MQTT Client
-keep class com.hivemq.client.** { *; }

# CameraX
-keep class androidx.camera.core.** { *; }

# Keep model files in assets
-keep class **.R$raw
-keep class **.R$asset
