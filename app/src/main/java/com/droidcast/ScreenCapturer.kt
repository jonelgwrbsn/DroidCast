package com.droidcast

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import org.webrtc.ScreenCapturerAndroid
import kotlin.math.min
import kotlin.math.roundToInt

data class CaptureDimensions(
    val width: Int,
    val height: Int,
    val fps: Int,
)

class ScreenCapturer(private val context: Context) {
    private var capturer: ScreenCapturerAndroid? = null
    private var activeDimensions: CaptureDimensions = computeCaptureDimensions(QualityPreset.ADAPTIVE)
    private var activeTier: AdaptiveTier = AdaptiveTier.fromPreset(QualityPreset.ADAPTIVE)
    private var lastReportedContentBounds: Pair<Int, Int>? = null
    private var lastDisplayLandscape: Boolean? = null
    private var resizeCallback: ((CaptureDimensions) -> Unit)? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    fun computeCaptureDimensions(preset: QualityPreset): CaptureDimensions =
        computeCaptureDimensions(AdaptiveTier.fromPreset(preset))

    fun computeCaptureDimensions(tier: AdaptiveTier): CaptureDimensions {
        val (screenW, screenH) = lastReportedContentBounds ?: readDeviceScreenPixels()
        return computeCaptureDimensionsForBounds(
            boundsW = screenW,
            boundsH = screenH,
            maxWidth = tier.maxWidth,
            maxHeight = tier.maxHeight,
            fps = tier.fps,
        )
    }

    fun computeCaptureDimensionsForBounds(
        boundsW: Int,
        boundsH: Int,
        preset: QualityPreset,
    ): CaptureDimensions = computeCaptureDimensionsForBounds(
        boundsW = boundsW,
        boundsH = boundsH,
        maxWidth = preset.maxWidth,
        maxHeight = preset.maxHeight,
        fps = preset.fps,
    )

    fun computeCaptureDimensionsForBounds(
        boundsW: Int,
        boundsH: Int,
        maxWidth: Int,
        maxHeight: Int,
        fps: Int,
    ): CaptureDimensions {
        val safeW = boundsW.coerceAtLeast(320)
        val safeH = boundsH.coerceAtLeast(320)

        val isLandscape = safeW > safeH
        val targetLongEdge = if (isLandscape) maxWidth else maxHeight
        val targetShortEdge = if (isLandscape) maxHeight else maxWidth

        val longEdge = maxOf(safeW, safeH)
        val shortEdge = minOf(safeW, safeH)
        val scale = min(
            1.0,
            min(
                targetLongEdge.toDouble() / longEdge.toDouble(),
                targetShortEdge.toDouble() / shortEdge.toDouble(),
            )
        )

        // Ensure even dimensions for H.264/VP8 hardware encoders
        val scaledW = (((safeW * scale).roundToInt()) / 2) * 2
        val scaledH = (((safeH * scale).roundToInt()) / 2) * 2

        return CaptureDimensions(
            width = scaledW.coerceAtLeast(320),
            height = scaledH.coerceAtLeast(320),
            fps = fps,
        )
    }

    @Suppress("DEPRECATION")
    private fun readDeviceScreenPixels(): Pair<Int, Int> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val rotation = display.rotation
            val isRotatedLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

            val mode = display.mode
            if (mode.physicalWidth > 0 && mode.physicalHeight > 0) {
                val longEdge = maxOf(mode.physicalWidth, mode.physicalHeight)
                val shortEdge = minOf(mode.physicalWidth, mode.physicalHeight)
                return if (isRotatedLandscape) longEdge to shortEdge else shortEdge to longEdge
            }

            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                val longEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
                val shortEdge = minOf(metrics.widthPixels, metrics.heightPixels)
                return if (isRotatedLandscape) longEdge to shortEdge else shortEdge to longEdge
            }
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val resMetrics = context.resources.displayMetrics
        return resMetrics.widthPixels to resMetrics.heightPixels
    }

    fun createCapturer(
        projectionResultData: Intent,
        onProjectionStopped: () -> Unit,
        onContentResized: ((CaptureDimensions) -> Unit)? = null,
    ): ScreenCapturerAndroid {
        stopAndDispose()
        lastReportedContentBounds = null
        resizeCallback = onContentResized
        val initialPixels = readDeviceScreenPixels()
        lastDisplayLandscape = initialPixels.first > initialPixels.second

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                DroidLog.w(DroidLog.TAG_SCREEN, "MediaProjection system session stopped")
                onProjectionStopped()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width <= 0 || height <= 0) return
                applyResolvedBounds(width, height, source = "content resize")
            }
        }
        val screenCapturer = ScreenCapturerAndroid(projectionResultData, callback)
        capturer = screenCapturer
        registerDisplayRotationListener()
        DroidLog.i(DroidLog.TAG_SCREEN, "Created ScreenCapturerAndroid instance")
        return screenCapturer
    }

    private fun registerDisplayRotationListener() {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        displayManager = dm
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY || capturer == null) return
                val (w, h) = readDeviceScreenPixels()
                val isLandscape = w > h
                val prevOrientation = lastDisplayLandscape
                if (prevOrientation == null || prevOrientation != isLandscape) {
                    lastDisplayLandscape = isLandscape
                    // Clear stale content bounds orientation so rotation takes effect immediately
                    applyResolvedBounds(w, h, source = "display rotation")
                }
            }
        }
        displayListener = listener
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    private fun applyResolvedBounds(width: Int, height: Int, source: String) {
        lastReportedContentBounds = width to height
        val dims = computeCaptureDimensionsForBounds(
            boundsW = width,
            boundsH = height,
            maxWidth = activeTier.maxWidth,
            maxHeight = activeTier.maxHeight,
            fps = activeTier.fps,
        )
        if (dims != activeDimensions) {
            activeDimensions = dims
            try {
                capturer?.changeCaptureFormat(dims.width, dims.height, dims.fps)
                DroidLog.i(
                    DroidLog.TAG_SCREEN,
                    "Updated capture format ($source) -> ${dims.width}x${dims.height} @ ${dims.fps}fps"
                )
                resizeCallback?.invoke(dims)
            } catch (e: Exception) {
                DroidLog.w(DroidLog.TAG_SCREEN, "Resize changeCaptureFormat warning: ${e.message}")
            }
        }
    }

    fun start(preset: QualityPreset): CaptureDimensions =
        start(AdaptiveTier.fromPreset(preset))

    fun start(tier: AdaptiveTier): CaptureDimensions {
        activeTier = tier
        val dims = computeCaptureDimensions(tier)
        activeDimensions = dims
        capturer?.startCapture(dims.width, dims.height, dims.fps)
        DroidLog.i(DroidLog.TAG_SCREEN, "Started VirtualDisplay capture at ${dims.width}x${dims.height} @ ${dims.fps}fps")
        return dims
    }

    fun updateFormat(preset: QualityPreset): CaptureDimensions =
        updateFormat(AdaptiveTier.fromPreset(preset))

    fun updateFormat(tier: AdaptiveTier): CaptureDimensions {
        activeTier = tier
        val dims = computeCaptureDimensions(tier)
        if (dims == activeDimensions) {
            return dims
        }
        activeDimensions = dims
        try {
            capturer?.changeCaptureFormat(dims.width, dims.height, dims.fps)
            DroidLog.i(DroidLog.TAG_SCREEN, "Updated capture format to ${dims.width}x${dims.height} @ ${dims.fps}fps")
        } catch (e: Exception) {
            DroidLog.w(DroidLog.TAG_SCREEN, "changeCaptureFormat warning: ${e.message}")
        }
        return dims
    }

    fun getMediaProjection(): MediaProjection? = capturer?.mediaProjection

    fun currentDimensions(): CaptureDimensions = activeDimensions

    fun stopAndDispose() {
        val listener = displayListener
        displayListener = null
        if (listener != null) {
            try {
                displayManager?.unregisterDisplayListener(listener)
            } catch (_: Exception) {
            }
        }
        displayManager = null
        resizeCallback = null
        lastDisplayLandscape = null

        val current = capturer
        capturer = null
        lastReportedContentBounds = null
        if (current != null) {
            try {
                current.stopCapture()
            } catch (e: Exception) {
                DroidLog.w(DroidLog.TAG_SCREEN, "stopCapture warning: ${e.message}")
            }
            try {
                current.dispose()
            } catch (_: Exception) {
            }
            DroidLog.i(DroidLog.TAG_SCREEN, "Disposed ScreenCapturerAndroid")
        }
    }
}
