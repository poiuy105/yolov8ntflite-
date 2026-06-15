# 第一阶段：核心稳定性优化实现计划

> **Goal:** 将 Demo 级应用升级为可 7x24 小时稳定运行的工业级边缘 AI 监控节点
> **Architecture:** 提取核心检测逻辑到 Foreground Service，引入帧率限制和 Bitmap 池降低资源消耗，增加 MQTT 重连和心跳机制提升可靠性
> **Tech Stack:** Kotlin, Android Foreground Service, CameraX, TensorFlow Lite, HiveMQ MQTT Client, Coroutines

---

## 文件结构

### 新建文件
- `app/src/main/java/com/example/cameradetect/BitmapPool.kt` — Bitmap 对象池，消除每帧创建/销毁
- `app/src/main/java/com/example/cameradetect/InferenceThrottler.kt` — 推理帧率限制器
- `app/src/main/java/com/example/cameradetect/BatteryMonitor.kt` — 电池状态监控（电量、温度、充电状态）
- `app/src/main/java/com/example/cameradetect/WatchdogHeartbeat.kt` — 看门狗心跳定时发送器
- `app/src/main/java/com/example/cameradetect/DetectionForegroundService.kt` — 前台服务，承载核心检测逻辑

### 修改文件
- `app/src/main/java/com/example/cameradetect/MainActivity.kt` — 改为 Service 控制界面
- `app/src/main/java/com/example/cameradetect/MqttManager.kt` — 添加断线重连机制
- `app/src/main/java/com/example/cameradetect/YoloDetector.kt` — 使用 BitmapPool
- `app/src/main/AndroidManifest.xml` — 添加 Service 声明、前台服务权限
- `app/src/main/res/xml/root_preferences.xml` — 添加新配置项
- `app/build.gradle.kts` — 确认无需新增依赖

---

## Task 1: BitmapPool — Bitmap 对象池

**Files:**
- Create: `app/src/main/java/com/example/cameradetect/BitmapPool.kt`
- Modify: `app/src/main/java/com/example/cameradetect/YoloDetector.kt`

**设计:** 固定大小为 3 的同步队列，acquire/release 模式。由于 CameraX 回调是单线程的（通过 setAnalyzer 的 executor），实际上不需要复杂同步，但用 ArrayDeque + synchronized 保证安全。

- [ ] **Step 1: 创建 BitmapPool.kt**

```kotlin
package com.example.cameradetect

import android.graphics.Bitmap
import java.util.ArrayDeque

class BitmapPool(private val width: Int, private val height: Int, private val config: Bitmap.Config) {
    private val pool = ArrayDeque<Bitmap>(3)
    private val maxSize = 3

    init {
        for (i in 0 until maxSize) {
            pool.addLast(Bitmap.createBitmap(width, height, config))
        }
    }

    @Synchronized
    fun acquire(): Bitmap {
        return if (pool.isNotEmpty()) {
            pool.removeFirst()
        } else {
            Bitmap.createBitmap(width, height, config)
        }
    }

    @Synchronized
    fun release(bitmap: Bitmap) {
        if (pool.size < maxSize && bitmap.width == width && bitmap.height == height && bitmap.config == config) {
            bitmap.eraseColor(0)
            pool.addLast(bitmap)
        } else {
            bitmap.recycle()
        }
    }

    @Synchronized
    fun clear() {
        pool.forEach { it.recycle() }
        pool.clear()
    }
}
```

- [ ] **Step 2: 在 YoloDetector 中使用 BitmapPool**

修改 `detect()` 方法：移除 `Bitmap.createScaledBitmap`（因为 ImageProcessor 已经做了 resize），直接使用 BitmapPool 获取/释放 Bitmap。

实际上，由于 YoloDetector 的 `detect()` 接收的是已经 resize 好的 Bitmap，BitmapPool 应该在 MainActivity/Service 的 `processImage()` 中使用。所以 Step 2 改为在 Service 中使用。

修正：BitmapPool 用于 `toBitmap(imageProxy)` 中创建的 Bitmap。在 Service 的 imageProxy 处理中使用。

---

## Task 2: InferenceThrottler — 推理帧率限制器

**Files:**
- Create: `app/src/main/java/com/example/cameradetect/InferenceThrottler.kt`

**设计:** 基于 `SystemClock.elapsedRealtime()` 的简易节流器，返回 true/false 表示是否允许本次推理。

- [ ] **Step 1: 创建 InferenceThrottler.kt**

```kotlin
package com.example.cameradetect

import android.os.SystemClock

class InferenceThrottler(var intervalMs: Long = 500L) {
    private var lastInferenceTime = 0L

    fun shouldProcess(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return if (now - lastInferenceTime >= intervalMs) {
            lastInferenceTime = now
            true
        } else {
            false
        }
    }

    fun reset() {
        lastInferenceTime = 0L
    }
}
```

---

## Task 3: MqttManager 断线重连

**Files:**
- Modify: `app/src/main/java/com/example/cameradetect/MqttManager.kt`

**设计:** 在现有 connect() 基础上，添加断线监听器和指数退避重连协程。

- [ ] **Step 1: 修改 MqttManager.kt**

添加：
1. `reconnectJob` 协程 Job
2. `scheduleReconnect()` 方法：指数退避（1s -> 2s -> 4s -> ... -> 60s）
3. 在 `connect()` 的 `whenComplete` 中，连接失败时调用 `scheduleReconnect()`
4. 添加 `MqttClientDisconnectedListener` 或定期检查连接状态

由于 HiveMQ 的异步 API 不直接提供断线回调，我们通过定期检查 `_isConnected` 状态或在 publish 失败时触发重连。

更简单的方案：在 `publishPersonCount` 中检测 `_isConnected`，如果断开则触发重连。同时添加一个定时检查（每 30 秒检查一次连接状态）。

---

## Task 4: BatteryMonitor — 电池状态监控

**Files:**
- Create: `app/src/main/java/com/example/cameradetect/BatteryMonitor.kt`

**设计:** 使用 `BroadcastReceiver` 监听 `ACTION_BATTERY_CHANGED`，提供电池电量、温度、充电状态的实时数据。

- [ ] **Step 1: 创建 BatteryMonitor.kt**

```kotlin
package com.example.cameradetect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

class BatteryMonitor(private val context: Context) {

    var batteryLevel: Int = -1
        private set
    var batteryTemperature: Float = 0f
        private set
    var isCharging: Boolean = false
        private set
    var voltage: Int = 0
        private set

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                batteryLevel = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (batteryLevel >= 0) {
                    batteryLevel = (batteryLevel * 100 / scale)
                }
                val temp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                batteryTemperature = temp / 10.0f
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            }
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(batteryReceiver, filter)
        batteryStatus?.let { batteryReceiver.onReceive(context, it) }
    }

    fun stop() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }
}
```

---

## Task 5: WatchdogHeartbeat — 看门狗心跳

**Files:**
- Create: `app/src/main/java/com/example/cameradetect/WatchdogHeartbeat.kt`

**设计:** 使用协程的 `delay()` 实现定时发送，消息包含设备状态、电池信息、运行时长。

- [ ] **Step 1: 创建 WatchdogHeartbeat.kt**

依赖 MqttManager 和 BatteryMonitor，通过回调获取当前检测状态。

---

## Task 6: DetectionForegroundService — 前台服务

**Files:**
- Create: `app/src/main/java/com/example/cameradetect/DetectionForegroundService.kt`

**设计:** 继承 `Service`，在 `onStartCommand` 中启动前台通知，在 `onCreate` 中初始化 CameraX、YoloDetector、MqttManager、WatchdogHeartbeat。

关键：
- 通知渠道 ID 和名称
- 通知显示内容：运行状态、人数、MQTT 状态
- Binder 机制供 Activity 绑定获取实时数据
- `onDestroy` 中清理所有资源

---

## Task 7: MainActivity 改造

**Files:**
- Modify: `app/src/main/java/com/example/cameradetect/MainActivity.kt`

**设计:**
- 移除 CameraX 和 YoloDetector 的初始化（移到 Service）
- 添加 Service 绑定逻辑
- 保留预览显示（通过 Service 提供的 Binder 获取预览 Surface）
- 添加启动/停止服务按钮
- 显示服务状态（运行中/已停止）、MQTT 连接状态

---

## Task 8: 配置、清单、权限更新

**Files:**
- Modify: `app/src/main/res/xml/root_preferences.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**设计:**
- 新增配置项：inference_fps, enable_preview, heartbeat_interval, auto_start_on_boot, enable_watchdog
- Manifest 添加 FOREGROUND_SERVICE 权限、Service 声明

---

## 执行顺序

1. Task 1 (BitmapPool) -> Task 2 (InferenceThrottler) -> Task 4 (BatteryMonitor) [独立工具类，可并行]
2. Task 3 (MqttManager 重连)
3. Task 6 (DetectionForegroundService) [依赖 1-4]
4. Task 5 (WatchdogHeartbeat) [依赖 Service 和 MqttManager]
5. Task 7 (MainActivity 改造) [依赖 Service]
6. Task 8 (配置和清单)
7. 编译验证
