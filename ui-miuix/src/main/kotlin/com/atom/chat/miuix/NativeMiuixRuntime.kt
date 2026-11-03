@file:Suppress("DEPRECATION")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.atom.chat.miuix

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.*
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.enableSavedStateHandles
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.ui.MiuixApp
import com.atom.chat.miuix.ui.miuixFontFamilyResolver
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

/** A render-thread-owned Compose scene. No Skia object crosses the JNI binding boundary. */
class NativeMiuixRuntime(private val host: MiuixHost) : AutoCloseable {
    companion object {
        /** CPU-only startup work; temporary composition never allocates a surface or GPU context. */
        @JvmStatic
        fun preloadResources() {
            NativeMiuixPreloader.preload()
        }
    }

    class PixelFrame(private val w: Int, private val h: Int, private val stride: Int,
                     private val pixels: Long, private val sequence: Long) {
        fun width() = w
        fun height() = h
        fun rowBytes() = stride
        fun address() = pixels
        fun generation() = sequence
    }

    private val ownerThread = Thread.currentThread()
    @Volatile private var dirty = true
    @Volatile private var frameRequested = true
    private var closed = false
    private val dispatcher = FrameDispatcher()
    private val job = Job()
    private var animationsEnabled = host.snapshot().animations()
    private val motionDurationScale = object : MotionDurationScale {
        override val scaleFactor get() = if (animationsEnabled) 1f else 0f
    }
    private val recomposer = FrameRecomposer(dispatcher + job + motionDurationScale) { frameRequested = true }
    private val architecture = DefaultArchitectureComponentsOwner(enforceMainThread = false).apply {
        enableSavedStateHandles()
        setLifecycleState(Lifecycle.State.RESUMED)
    }
    private val window = object : WindowInfo {
        override var isWindowFocused by mutableStateOf(true)
        override var containerSize by mutableStateOf(IntSize.Zero)
        override var containerDpSize by mutableStateOf(DpSize.Zero)
        override var keyboardModifiers by mutableStateOf(PointerKeyboardModifiers())
    }
    private var editCommands: ((List<EditCommand>) -> Unit)? = null
    private var legacyValue: TextFieldValue? = null
    private var composing = false
    private var inputRequest: PlatformTextInputMethodRequest? = null
    private val semanticsOwners = mutableListOf<SemanticsOwner>()
    private val legacyInput = object : PlatformTextInputService {
        override fun startInput(value: TextFieldValue, imeOptions: ImeOptions,
                                onEditCommand: (List<EditCommand>) -> Unit,
                                onImeActionPerformed: (ImeAction) -> Unit) {
            inputRequest = null
            editCommands = onEditCommand
            legacyValue = value
            composing = false
            host.requestTextInput(true, 0f, 0f, 0f, 0f)
        }
        override fun stopInput() {
            // A delayed legacy stop must not tear down its modern replacement.
            if (inputRequest != null) return
            editCommands = null
            legacyValue = null
            composing = false
            host.requestTextInput(false, 0f, 0f, 0f, 0f)
        }
        override fun showSoftwareKeyboard() = Unit
        override fun hideSoftwareKeyboard() = Unit
        override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) { legacyValue = newValue }
        override fun notifyFocusedRect(rect: androidx.compose.ui.geometry.Rect) {
            if (inputRequest == null && editCommands != null)
                host.requestTextInput(true, rect.left, rect.top, rect.width, rect.height)
        }
    }
    private val platform = object : PlatformContext by PlatformContext.Empty() {
        override val windowInfo = window
        override val architectureComponentsOwner = architecture
        override val isWindowTransparent = true
        override val textInputService = legacyInput
        override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
            override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { semanticsOwners.add(semanticsOwner) }
            override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { semanticsOwners.remove(semanticsOwner) }
            override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit
            override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
        }
        override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
            val callback = request.onEditCommand
            editCommands = callback
            legacyValue = request.value()
            composing = false
            inputRequest = request
            updateInputAnchor()
            try { awaitCancellation() } finally {
                // This request owns its anchor independently of the legacy API's
                // callback. Otherwise a legacy stop can leave a detached request
                // reviving OS text input on every subsequent rendered frame.
                if (inputRequest === request) {
                    editCommands = null
                    legacyValue = null
                    composing = false
                    inputRequest = null
                    host.requestTextInput(false, 0f, 0f, 0f, 0f)
                }
            }
        }
    }
    private val clipboard = object : Clipboard {
        override val nativeClipboard: Any get() = this
        override suspend fun getClipEntry(): ClipEntry? =
            host.readClipboard()?.let { ClipEntry(StringSelection(it)) }
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            val transferable = clipEntry?.nativeClipEntry as? Transferable ?: return
            if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                host.writeClipboard(transferable.getTransferData(DataFlavor.stringFlavor) as String)
            }
        }
    }
    private val clipboardManager = object : ClipboardManager {
        override fun getText(): AnnotatedString? = host.readClipboard()?.let(::AnnotatedString)
        override fun setText(annotatedString: AnnotatedString) = host.writeClipboard(annotatedString.text)
        override fun hasText() = !host.readClipboard().isNullOrEmpty()
    }
    private var state by mutableStateOf(host.snapshot())
    private var worldBackdropRequired by mutableStateOf(host.worldBackdropRequired(state))
    private var worldImage by mutableStateOf<Image?>(null)
    private var savedSceneState: Map<String, List<Any?>>? = null
    private var sceneRegistry: SaveableStateRegistry? = null
    private var sceneOwner: ComposeScene? = null
    private var sceneDisposalFailed = false
    private val scene: ComposeScene
        get() = sceneOwner ?: createScene().also { sceneOwner = it }

    private fun createScene(): ComposeScene {
        // This state lives only within the current host, never serialized across processes.
        val registry = SaveableStateRegistry(savedSceneState) { true }
        sceneRegistry = registry
        savedSceneState = null
        return CanvasLayersComposeScene(
            frameRecomposer = recomposer,
            platformContext = platform,
            invalidateLayout = { dirty = true },
            invalidateDraw = { dirty = true }
        ).apply {
            if (viewportWidth > 0 && viewportHeight > 0) {
                size = IntSize(viewportWidth, viewportHeight)
                density = Density(scale)
            }
            setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard,
                    LocalClipboardManager provides clipboardManager,
                    LocalFontFamilyResolver provides miuixFontFamilyResolver(),
                    LocalSaveableStateRegistry provides registry) {
                    MiuixApp(host, state, worldImage, worldBackdropRequired)
                }
            }
        }
    }

    /** Called in the owning renderer's live context before any of its GPU resources are closed. */
    internal fun releaseScene() {
        checkThread()
        val previous = sceneOwner ?: return
        cancelComposingText()
        var failure: Throwable? = null
        try {
            savedSceneState = sceneRegistry?.performSave()
        } catch (error: Throwable) {
            failure = error
            savedSceneState = null
        }
        sceneOwner = null
        sceneRegistry = null
        try {
            previous.close()
            dispatcher.drain()
        } catch (error: Throwable) {
            sceneDisposalFailed = true
            val earlier = failure
            if (earlier == null) failure = error else earlier.addSuppressed(error)
        } finally {
            semanticsOwners.clear()
            editCommands = null
            legacyValue = null
            composing = false
            inputRequest = null
            pressedButtons = 0
            host.requestTextInput(false, 0f, 0f, 0f, 0f)
            prepared = false
            frameRequested = true
            dirty = true
        }
        failure?.let { throw it }
    }
    private var surface: Surface? = null
    private var pixmap: Pixmap? = null
    private var gpuContext: DirectContext? = null
    private var gpuTarget: BackendRenderTarget? = null
    private var gpuContextToken = Long.MIN_VALUE
    private var gpuFramebuffer = 0
    private var frame: PixelFrame? = null
    private var sequence = 0L
    private var scale = 1f
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var prepared = false
    private var preparedNanos = Long.MIN_VALUE
    private val profileEnabled = java.lang.Boolean.getBoolean("atomchat.automation.performance")
    private var prepareCalls = 0L
    private var rasterRenderCalls = 0L
    private var gpuRenderCalls = 0L
    private var frameUpdates = 0L
    private var rasterDrawCalls = 0L
    private var gpuDrawCalls = 0L
    private var worldImports = 0L
    private var worldCrops = 0L
    private var worldGpuCrops = 0L
    private var worldRasterCrops = 0L
    private var hostSnapshotNanos = 0L
    private var frameUpdateNanos = 0L
    private var drawNanos = 0L
    private var gpuSubmitNanos = 0L
    private var worldImportNanos = 0L
    private var worldCropNanos = 0L
    private var pressedButtons = 0
    private var sourceImage: Image? = null
    private var sourceGeneration = Long.MIN_VALUE
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var lastCrop: List<Float>? = null
    private data class GpuWorldFrame(val framebuffer: Int, val width: Int, val height: Int,
        val generation: Long, val cropX: Float, val cropY: Float, val cropWidth: Float, val cropHeight: Float)
    private var pendingGpuWorld: GpuWorldFrame? = null
    private data class CpuWorldFrame(val width: Int, val height: Int, val rgba: ByteArray,
        val generation: Long, val cropX: Float, val cropY: Float, val cropWidth: Float, val cropHeight: Float)
    private var pendingCpuWorld: CpuWorldFrame? = null
    private var worldGpuTarget: BackendRenderTarget? = null
    private var worldGpuSurface: Surface? = null
    private var worldGpuFramebuffer = 0
    private var worldImageGpu = false

    fun render(width: Int, height: Int, displayScale: Float, nanos: Long): PixelFrame? {
        checkThread()
        if (width <= 0 || height <= 0) return null
        rasterRenderCalls++
        if (gpuContext != null) releaseGpu()
        if (surface?.width != width || surface?.height != height) {
            val oldPixels = pixmap
            val oldSurface = surface
            pixmap = null
            surface = null
            oldPixels?.close()
            oldSurface?.close()
            surface = Surface.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
            pixmap = Pixmap().also { check(surface!!.peekPixels(it)) }
            frame = null
            dirty = true
        }
        importCpuWorld(null)
        ensurePrepared(width, height, displayScale, nanos)
        if (drawPrepared()) {
            rasterDrawCalls++
            val pixels = pixmap!!
            frame = PixelFrame(width, height, pixels.rowBytes, pixels.addr, sequence)
        }
        return frame
    }

    /** Advances Compose without touching an OpenGL context; the host may skip an idle GPU pass. */
    fun prepareFrame(width: Int, height: Int, displayScale: Float, nanos: Long): Boolean {
        checkThread()
        if (width <= 0 || height <= 0) return false
        prepareCalls++
        resizeViewport(width, height, displayScale)
        val snapshotStarted = profileStart()
        state = host.snapshot()
        // A native transition can settle without changing its domain snapshot.
        // Observe the demand separately so its last hidden world layer unmounts.
        worldBackdropRequired = host.worldBackdropRequired(state)
        animationsEnabled = state.animations()
        hostSnapshotNanos += profileElapsed(snapshotStarted)
        val updateStarted = profileStart()
        dispatcher.drain()
        if (dirty || frameRequested || recomposer.hasPendingWork() || scene.hasInvalidations()) {
            frameUpdates++
            frameRequested = false
            recomposer.performFrame(nanos)
            dispatcher.drain()
            scene.measureAndLayout()
            updateInputAnchor()
        }
        frameUpdateNanos += profileElapsed(updateStarted)
        prepared = true
        preparedNanos = nanos
        return dirty || scene.hasPendingDraw
    }

    /** Input dispatch may measure synchronously, so size must be set before its first event. */
    fun resizeViewport(width: Int, height: Int, displayScale: Float) {
        checkThread()
        require(width > 0 && height > 0) { "Invalid Compose viewport" }
        val newScale = displayScale.coerceAtLeast(0.01f)
        if (viewportWidth != width || viewportHeight != height || newScale != scale) {
            viewportWidth = width
            viewportHeight = height
            scale = newScale
            scene.size = IntSize(width, height)
            scene.density = Density(scale)
            window.containerSize = IntSize(width, height)
            window.containerDpSize = DpSize((width / scale).dp, (height / scale).dp)
            dirty = true
            prepared = false
        }
    }

    /** Draws into a host-owned framebuffer in the currently active native OpenGL context. */
    fun renderGpu(framebuffer: Int, width: Int, height: Int, displayScale: Float,
                  nanos: Long, contextToken: Long): Long {
        checkThread()
        require(framebuffer > 0 && width > 0 && height > 0) { "Invalid Compose GPU target" }
        gpuRenderCalls++
        check(gpuContext == null || gpuContextToken == contextToken) {
            "Release the Compose GPU context before changing the native OpenGL session"
        }
        if (gpuContext == null) {
            pixmap?.close()
            pixmap = null
            surface?.close()
            surface = null
            frame = null
            gpuContext = DirectContext.makeGL()
            gpuContextToken = contextToken
        }
        val context = gpuContext!!
        context.resetGLAll()
        if (gpuFramebuffer != framebuffer || surface?.width != width || surface?.height != height) {
            val oldSurface = surface
            val oldTarget = gpuTarget
            surface = null
            gpuTarget = null
            oldSurface?.close()
            oldTarget?.close()
            gpuTarget = BackendRenderTarget.makeGL(width, height, 0, 8, framebuffer, 0x8058) // GL_RGBA8
            surface = requireNotNull(Surface.makeFromBackendRenderTarget(context, gpuTarget!!,
                SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB)) {
                "Unable to wrap the Compose GPU framebuffer"
            }
            gpuFramebuffer = framebuffer
            dirty = true
        }
        importGpuWorld(context)
        importCpuWorld(context)
        ensurePrepared(width, height, displayScale, nanos)
        if (drawPrepared()) {
            gpuDrawCalls++
            val submitStarted = profileStart()
            surface!!.flushAndSubmit(false)
            gpuSubmitNanos += profileElapsed(submitStarted)
        }
        return sequence
    }

    /** The host calls this while this session's native OpenGL context is still current. */
    fun releaseGpuTarget() {
        checkThread()
        if (gpuContext == null) return
        val oldSurface = surface
        val oldTarget = gpuTarget
        surface = null
        gpuTarget = null
        gpuFramebuffer = 0
        frame = null
        prepared = false
        dirty = true
        try {
            oldSurface?.close()
        } finally {
            oldTarget?.close()
        }
    }

    /** The host calls this while this session's native OpenGL context is still current. */
    fun releaseGpu() {
        checkThread()
        val context = gpuContext ?: return
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        // Display lists can retain GPU images after our Image wrapper closes.
        // Dispose their Compose owners while this native GL context is still live.
        cleanup { releaseScene() }
        val oldSurface = surface
        val oldTarget = gpuTarget
        surface = null
        gpuTarget = null
        gpuContext = null
        gpuContextToken = Long.MIN_VALUE
        gpuFramebuffer = 0
        frame = null
        dirty = true
        // Detach first so one failed native destructor cannot skip the remaining resources.
        if (worldImageGpu || worldGpuSurface != null || worldGpuTarget != null) {
            val oldWorldImage = worldImage
            val oldSourceImage = sourceImage
            val oldWorldSurface = worldGpuSurface
            val oldWorldTarget = worldGpuTarget
            worldImage = null
            sourceImage = null
            sourceGeneration = Long.MIN_VALUE
            lastCrop = null
            worldGpuSurface = null
            worldGpuTarget = null
            worldGpuFramebuffer = 0
            worldImageGpu = false
            cleanup { oldWorldImage?.close() }
            cleanup { oldSourceImage?.close() }
            cleanup { oldWorldSurface?.close() }
            cleanup { oldWorldTarget?.close() }
        }
        cleanup { oldSurface?.close() }
        cleanup { oldTarget?.close() }
        if (failure != null || sceneDisposalFailed) cleanup { context.abandon() }
        cleanup { context.close() }
        failure?.let { throw it }
    }

    /** Cumulative diagnostics; stage timing is enabled only for performance automation. */
    fun statistics(): Map<String, Long> = linkedMapOf(
        "prepareCalls" to prepareCalls, "rasterRenderCalls" to rasterRenderCalls,
        "gpuRenderCalls" to gpuRenderCalls, "frameUpdates" to frameUpdates,
        "rasterDrawCalls" to rasterDrawCalls, "gpuDrawCalls" to gpuDrawCalls,
        "generation" to sequence, "worldImports" to worldImports, "worldCrops" to worldCrops,
        "worldGpuCrops" to worldGpuCrops, "worldRasterCrops" to worldRasterCrops,
        "hostSnapshotNanos" to hostSnapshotNanos, "frameUpdateNanos" to frameUpdateNanos,
        "drawNanos" to drawNanos, "gpuSubmitNanos" to gpuSubmitNanos,
        "worldImportNanos" to worldImportNanos, "worldCropNanos" to worldCropNanos
    )

    private fun ensurePrepared(width: Int, height: Int, displayScale: Float, nanos: Long) {
        if (!prepared || preparedNanos != nanos || viewportWidth != width || viewportHeight != height ||
            scale != displayScale.coerceAtLeast(0.01f)) {
            prepareFrame(width, height, displayScale, nanos)
        }
    }

    private fun drawPrepared(): Boolean {
        prepared = false
        // Polling can advance without changing observed state; retain the previous GPU/pixel frame.
        if (!dirty && !scene.hasPendingDraw) return false
        val started = profileStart()
        dirty = false
        try {
            surface!!.canvas.clear(0)
            scene.draw(surface!!.canvas.asComposeCanvas())
            sequence++
            return true
        } catch (failure: Throwable) {
            dirty = true
            throw failure
        } finally {
            drawNanos += profileElapsed(started)
        }
    }

    private fun profileStart() = if (profileEnabled) System.nanoTime() else 0L
    private fun profileElapsed(started: Long) = if (started == 0L) 0L else System.nanoTime() - started

    /** Metadata only: importing runs later while the host's native GL context is active. */
    fun setWorldGpuFrame(framebuffer: Int, width: Int, height: Int, generation: Long,
                         cropX: Float, cropY: Float, cropWidth: Float, cropHeight: Float) {
        checkThread()
        if (framebuffer <= 0 || width <= 0 || height <= 0 || cropWidth <= 0 || cropHeight <= 0) return
        val next = GpuWorldFrame(framebuffer, width, height, generation, cropX, cropY, cropWidth, cropHeight)
        pendingCpuWorld = null
        if (pendingGpuWorld != next) {
            pendingGpuWorld = next
            dirty = true
            prepared = false
        }
    }

    private fun importGpuWorld(context: DirectContext) {
        if (!host.worldBackdropRequired(state)) return
        val next = pendingGpuWorld ?: return
        var changed = false
        if (worldGpuFramebuffer != next.framebuffer || sourceWidth != next.width || sourceHeight != next.height) {
            worldGpuSurface?.close()
            worldGpuTarget?.close()
            worldGpuTarget = BackendRenderTarget.makeGL(next.width, next.height, 0, 0, next.framebuffer, 0x8058)
            worldGpuSurface = requireNotNull(Surface.makeFromBackendRenderTarget(context, worldGpuTarget!!,
                SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB)) {
                "Unable to wrap the retained world framebuffer"
            }
            worldGpuFramebuffer = next.framebuffer
            changed = true
        }
        if (changed || sourceGeneration != next.generation) {
            val started = profileStart()
            sourceImage?.close()
            // The external producer has replaced this FBO since the last submit.
            worldGpuSurface!!.notifyContentWillChange(ContentChangeMode.DISCARD)
            sourceImage = worldGpuSurface!!.makeImageSnapshot()
            sourceWidth = next.width
            sourceHeight = next.height
            sourceGeneration = next.generation
            worldImports++
            worldImportNanos += profileElapsed(started)
            changed = true
        }
        val crop = listOf(next.cropX, next.cropY, next.cropWidth, next.cropHeight)
        if (changed || crop != lastCrop) {
            val started = profileStart()
            val width = kotlin.math.ceil(next.cropWidth.toDouble()).toInt().coerceAtLeast(1)
            val height = kotlin.math.ceil(next.cropHeight.toDouble()).toInt().coerceAtLeast(1)
            val cropped = Surface.makeRenderTarget(context, false,
                ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)).use { target ->
                target.canvas.clear(0)
                target.canvas.drawImageRect(sourceImage!!,
                    Rect.makeXYWH(next.cropX, next.cropY, next.cropWidth, next.cropHeight),
                    Rect.makeWH(width.toFloat(), height.toFloat()))
                target.makeImageSnapshot()
            }
            val previous = worldImage
            worldImage = cropped
            worldImageGpu = true
            previous?.close()
            lastCrop = crop
            worldCrops++
            worldGpuCrops++
            worldCropNanos += profileElapsed(started)
            dirty = true
            prepared = false
        }
    }

    /** Import the unblurred game source, then crop it to this scene's physical bounds. */
    fun setWorldFrame(width: Int, height: Int, rgba: ByteArray, generation: Long,
                      cropX: Float, cropY: Float, cropWidth: Float, cropHeight: Float) {
        checkThread()
        if (width <= 0 || height <= 0 || rgba.size.toLong() < width.toLong() * height * 4 ||
            cropWidth <= 0 || cropHeight <= 0) return
        pendingGpuWorld = null
        val next = CpuWorldFrame(width, height, rgba, generation, cropX, cropY, cropWidth, cropHeight)
        val previous = pendingCpuWorld
        // Source generation owns pixel identity. Hosts may pass another view of an unchanged buffer.
        if (previous == null || previous.generation != generation || previous.width != width || previous.height != height ||
            previous.cropX != cropX || previous.cropY != cropY || previous.cropWidth != cropWidth || previous.cropHeight != cropHeight) {
            pendingCpuWorld = next
            dirty = true
            prepared = false
        }
    }

    private fun importCpuWorld(context: DirectContext?) {
        if (!host.worldBackdropRequired(state)) return
        val next = pendingCpuWorld ?: return
        val (width, height, rgba, generation, cropX, cropY, cropWidth, cropHeight) = next
        var changed = false
        if (sourceGeneration != generation || sourceWidth != width || sourceHeight != height) {
            val importStarted = profileStart()
            val imported = Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), rgba, width * 4)
            sourceImage?.close()
            sourceImage = imported
            sourceWidth = width
            sourceHeight = height
            sourceGeneration = generation
            worldImports++
            worldImportNanos += profileElapsed(importStarted)
            changed = true
        }
        val crop = listOf(cropX, cropY, cropWidth, cropHeight)
        if (changed || crop != lastCrop) {
            val cropStarted = profileStart()
            val targetWidth = kotlin.math.ceil(cropWidth.toDouble()).toInt().coerceAtLeast(1)
            val targetHeight = kotlin.math.ceil(cropHeight.toDouble()).toInt().coerceAtLeast(1)
            val info = ImageInfo(targetWidth, targetHeight, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            // Vulkan must upload its source across APIs, but Compose's crop and blur stay GPU work.
            val cropped = (if (context == null) Surface.makeRaster(info)
                else Surface.makeRenderTarget(context, false, info)).use { target ->
                target.canvas.clear(0)
                target.canvas.drawImageRect(sourceImage!!,
                    Rect.makeXYWH(cropX, cropY, cropWidth, cropHeight),
                    Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()))
                target.makeImageSnapshot()
            }
            val previous = worldImage
            worldImage = cropped
            worldImageGpu = context != null
            previous?.close()
            lastCrop = crop
            worldCrops++
            if (context == null) worldRasterCrops++ else worldGpuCrops++
            worldCropNanos += profileElapsed(cropStarted)
            dirty = true
            prepared = false
        }
    }

    fun pointer(eventType: Int, x: Float, y: Float, scrollX: Float, scrollY: Float,
                button: Int, modifiers: Int) {
        checkThread()
        val pointerButton = when (button) { 0 -> PointerButton.Primary; 1 -> PointerButton.Secondary; 2 -> PointerButton.Tertiary; else -> null }
        val mask = when (button) { 0 -> 1; 1 -> 2; 2 -> 4; else -> 0 }
        if (eventType == 1) pressedButtons = pressedButtons or mask
        if (eventType == 2) pressedButtons = pressedButtons and mask.inv()
        window.keyboardModifiers = modifiers(modifiers)
        scene.sendPointerEvent(
            eventType = when (eventType) { 1 -> PointerEventType.Press; 2 -> PointerEventType.Release;
                3 -> PointerEventType.Scroll; 4 -> PointerEventType.Enter; 5 -> PointerEventType.Exit; else -> PointerEventType.Move },
            position = Offset(x, y), scrollDelta = Offset(scrollX, scrollY),
            timeMillis = System.nanoTime() / 1_000_000,
            buttons = PointerButtons(isPrimaryPressed = pressedButtons and 1 != 0,
                isSecondaryPressed = pressedButtons and 2 != 0, isTertiaryPressed = pressedButtons and 4 != 0),
            keyboardModifiers = window.keyboardModifiers, button = pointerButton
        )
        // Compose invalidates the affected layout/draw owners itself, including
        // hover and drag state. A stationary move must not force a new GPU frame;
        // Compose also resends its last pointer position after layout changes.
    }

    fun key(awtKeyCode: Int, awtLocation: Int, down: Boolean, modifiers: Int): Boolean {
        checkThread()
        window.keyboardModifiers = modifiers(modifiers)
        // SDL delivers physical keys separately from its IME preedit/commit events.
        // The OS owns them until composition ends: Enter chooses a candidate,
        // arrows move inside it, and Escape dismisses it rather than the panel.
        if (composing) return true
        return scene.sendKeyEvent(KeyEvent(Key(awtKeyCode, awtLocation),
            if (down) KeyEventType.KeyDown else KeyEventType.KeyUp,
            isCtrlPressed = modifiers and 2 != 0, isMetaPressed = modifiers and 8 != 0,
            isAltPressed = modifiers and 4 != 0, isShiftPressed = modifiers and 1 != 0)).also { dirty = true }
    }

    fun text(text: String): Boolean {
        checkThread()
        dispatcher.drain()
        val edit = editCommands ?: return false
        updateFallbackValue(text, text.length, false)
        edit(listOf(CommitTextCommand(text, 1)))
        composing = false
        dirty = true
        return true
    }

    fun hasTextFocus(): Boolean = editCommands != null

    fun isComposingText(): Boolean = composing

    /** Scene-local physical bounds used by the development interaction probes. */
    fun boundsForTag(tag: String): FloatArray? {
        checkThread()
        for (owner in semanticsOwners.asReversed()) {
            for (node in owner.getAllSemanticsNodes(mergingEnabled = false).asReversed()) {
                if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) {
                    visibleBounds(node.boundsInRoot)?.let { return it }
                }
            }
        }
        return null
    }

    /** Exact visible labels also identify upstream popup items that cannot carry a test tag. */
    fun boundsForText(text: String): FloatArray? {
        checkThread()
        for (owner in semanticsOwners.asReversed()) {
            for (node in owner.getAllSemanticsNodes(mergingEnabled = false).asReversed()) {
                val labels = node.config.getOrNull(SemanticsProperties.Text) ?: continue
                if (labels.any { it.text == text }) {
                    visibleBounds(node.boundsInRoot)?.let { return it }
                }
            }
        }
        return null
    }

    private fun visibleBounds(bounds: androidx.compose.ui.geometry.Rect): FloatArray? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        if (!bounds.left.isFinite() || !bounds.top.isFinite() ||
            !bounds.right.isFinite() || !bounds.bottom.isFinite()) return null
        val left = maxOf(0f, bounds.left)
        val top = maxOf(0f, bounds.top)
        val right = minOf(viewportWidth.toFloat(), bounds.right)
        val bottom = minOf(viewportHeight.toFloat(), bounds.bottom)
        return if (right > left && bottom > top) floatArrayOf(left, top, right, bottom) else null
    }

    fun getTags(): List<String> {
        checkThread()
        return semanticsOwners.flatMap { owner ->
            owner.getAllSemanticsNodes(mergingEnabled = false).mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)
            }
        }.distinct()
    }

    fun composingText(text: String) {
        composingText(text, text.length)
    }

    /** Native preedit carets use UTF-16 offsets, matching Compose's selection API. */
    fun composingText(text: String, caretPosition: Int) {
        checkThread()
        dispatcher.drain()
        val edit = editCommands ?: return
        val value = currentTextValue() ?: return
        val start = value.composition?.min ?: value.selection.min
        val caret = start + caretPosition.coerceIn(0, text.length)
        updateFallbackValue(text, caretPosition, true)
        edit(listOf(SetComposingTextCommand(text, 1), SetSelectionCommand(caret, caret)))
        composing = text.isNotEmpty()
        dirty = true
    }

    fun finishComposingText() {
        checkThread()
        legacyValue = currentTextValue()?.copy(composition = null)
        editCommands?.invoke(listOf(FinishComposingTextCommand()))
        composing = false
        dirty = true
    }

    /** Minecraft clears preedit immediately before sending its committed characters. */
    fun cancelComposingText() {
        checkThread()
        if (!composing || currentTextValue()?.composition == null) return
        updateFallbackValue("", 0, false)
        editCommands?.invoke(listOf(SetComposingTextCommand("", 1), FinishComposingTextCommand()))
        composing = false
        dirty = true
    }

    fun setFocused(focused: Boolean) {
        checkThread()
        window.isWindowFocused = focused
        if (!focused) {
            pressedButtons = 0
            // SDL can omit the button-up while another window owns focus.
            // Clearing the mask alone leaves native drag coroutines suspended.
            scene.cancelPointerInput()
        }
        dirty = true
    }

    fun invalidate() { dirty = true; prepared = false }

    override fun close() {
        if (closed) return
        checkThread()
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        cleanup { releaseScene() }
        savedSceneState = null
        cleanup { recomposer.close() }
        job.cancel()
        cleanup { architecture.setLifecycleState(Lifecycle.State.DESTROYED) }
        editCommands = null
        legacyValue = null
        composing = false
        inputRequest = null
        cleanup { host.requestTextInput(false, 0f, 0f, 0f, 0f) }
        cleanup { releaseGpu() }
        cleanup { pixmap?.close() }
        cleanup { surface?.close() }
        cleanup { worldImage?.close() }
        cleanup { sourceImage?.close() }
        dispatcher.clear()
        closed = true
        failure?.let { throw it }
    }

    private fun checkThread() {
        check(!closed) { "Compose scene is closed" }
        check(Thread.currentThread() === ownerThread) { "Compose scene must run on its owning render thread" }
    }

    private fun modifiers(bits: Int) = PointerKeyboardModifiers(
        isShiftPressed = bits and 1 != 0, isCtrlPressed = bits and 2 != 0,
        isAltPressed = bits and 4 != 0, isMetaPressed = bits and 8 != 0)

    private fun updateInputAnchor() {
        if (editCommands == null) {
            host.requestTextInput(false, 0f, 0f, 0f, 0f)
            return
        }
        val request = inputRequest ?: return
        val rect = request.focusedRectInRoot()
        host.requestTextInput(true, rect?.left ?: 0f, rect?.top ?: 0f,
            rect?.width ?: 0f, rect?.height ?: 0f)
    }

    private fun currentTextValue(): TextFieldValue? = inputRequest?.value?.invoke() ?: legacyValue

    // The legacy platform receives updateState during composition. Mirror our
    // emitted commands so a preedit and its null/commit callbacks in the same
    // game event batch still see the current composition and UTF-16 selection.
    private fun updateFallbackValue(text: String, caretPosition: Int, compose: Boolean) {
        val value = currentTextValue() ?: return
        val replaced = value.composition ?: value.selection
        val start = replaced.min.coerceIn(0, value.text.length)
        val end = replaced.max.coerceIn(start, value.text.length)
        val caret = start + caretPosition.coerceIn(0, text.length)
        legacyValue = TextFieldValue(value.text.replaceRange(start, end, text), TextRange(caret),
            if (compose && text.isNotEmpty()) TextRange(start, start + text.length) else null)
    }

    private class FrameDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
        }
        fun drain() { while (true) (queue.poll() ?: break).run() }
        fun clear() = queue.clear()
    }
}
