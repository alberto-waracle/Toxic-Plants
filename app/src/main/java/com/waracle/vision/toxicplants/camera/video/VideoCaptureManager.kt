package com.waracle.vision.toxicplants.camera.video


import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.core.impl.utils.executor.CameraXExecutors
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.runtime.compositionLocalOf
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.*
import com.google.common.util.concurrent.ListenableFuture
import com.waracle.vision.toxicplants.camera.rotate
import com.waracle.vision.toxicplants.camera.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class VideoCaptureManager private constructor(private val builder: Builder) :
    LifecycleEventObserver {

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var videoCapture: VideoCapture<Recorder>

    private lateinit var activeRecording: Recording

    var listener: Listener? = null

    init {
        getLifecycle().addObserver(this)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_CREATE -> {
                cameraProviderFuture = ProcessCameraProvider.getInstance(getContext())
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    queryCameraInfo(source, cameraProvider)
                }, ContextCompat.getMainExecutor(getContext()))
            }
            else -> Unit
        }
    }

    /**
     * Queries the capabilities of the FRONT and BACK camera lens
     * The result is stored in an array map.
     *
     * With this, we can determine if a camera lens is available or not,
     * and what capabilities the lens can support e.g flash support
     */
    private fun queryCameraInfo(
        lifecycleOwner: LifecycleOwner,
        cameraProvider: ProcessCameraProvider
    ) {
        val cameraLensInfo = HashMap<Int, CameraInfo>()
        arrayOf(CameraSelector.LENS_FACING_BACK, CameraSelector.LENS_FACING_FRONT).forEach { lens ->
            val cameraSelector = CameraSelector.Builder().requireLensFacing(lens).build()
            if (cameraProvider.hasCamera(cameraSelector)) {
                val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector)
                if (lens == CameraSelector.LENS_FACING_BACK) {
                    cameraLensInfo[CameraSelector.LENS_FACING_BACK] = camera.cameraInfo
                } else if (lens == CameraSelector.LENS_FACING_FRONT) {
                    cameraLensInfo[CameraSelector.LENS_FACING_FRONT] = camera.cameraInfo
                }
            }
        }
        listener?.onInitialised(cameraLensInfo)
    }

    /**
     * Takes a [previewState] argument to determine the camera options
     *
     * Create a Preview.
     * Create Video Capture use case
     * Bind the selected camera and any use cases to the lifecycle.
     * Connect the Preview to the PreviewView.
     */
    @SuppressLint("RestrictedApi")
    fun showPreview(previewState: PreviewState, cameraPreview: PreviewView = getCameraPreview()): View {
        getLifeCycleOwner().lifecycleScope.launch {
            // repeatOnLifecycle will restart the coroutine when the lifecycle is resumed
            getLifecycle().repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val cameraProvider = cameraProviderFuture.await()
                cameraProvider.unbindAll()

                //Select a camera lens
                val cameraSelector: CameraSelector = CameraSelector.Builder()
                    .requireLensFacing(previewState.cameraLens)
                    .build()

                //Create Preview use case
                val preview: Preview = Preview.Builder()
                    .setTargetResolution(previewState.size)
                    .build()
                    .apply {
                        setSurfaceProvider(cameraPreview.surfaceProvider)
                    }

                //Create Video Capture use case
//                    val recorder = Recorder.Builder().build()
//                    videoCapture = VideoCapture.withOutput(recorder)

                //Create an Analyzer use case
                val imageAnalyzer = ImageAnalysis.Builder()
                    .apply {
                        setTargetResolution(previewState.size)
                        setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        build()
                    }
                    .run {
                        build()
                    }
                    .apply {
                        setAnalyzer(CameraXExecutors.ioExecutor()) { imageProxy ->

                            CoroutineScope(Dispatchers.IO).launch {
                                delay(700)
                                listener?.processFrame(imageProxy.toBitmap()?.rotate())
                                imageProxy.close()
                            }
                        }
                    }

                cameraProvider.bindToLifecycle(
                    getLifeCycleOwner(),
                    cameraSelector,
                    preview,
//                        videoCapture,
                    imageAnalyzer
                ).apply {
                    cameraControl.enableTorch(previewState.torchState == TorchState.ON)
                }
            }
        }
        return cameraPreview
    }

    fun updatePreview(previewState: PreviewState, previewView: View) {
        showPreview(previewState, previewView as PreviewView)
    }

    private fun getCameraPreview() = PreviewView(getContext()).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        keepScreenOn = true
    }

    private fun getLifecycle() = builder.lifecycleOwner?.lifecycle!!

    private fun getContext() = builder.context

    private fun getLifeCycleOwner() = builder.lifecycleOwner!!

    @SuppressLint("MissingPermission")
    fun startRecording(filePath: String) {
        val outputOptions = FileOutputOptions.Builder(File(filePath)).build()
        activeRecording = videoCapture.output
            .prepareRecording(getContext(), outputOptions)
            .apply { withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(getContext()), videoRecordingListener)
    }

    fun pauseRecording() {
        activeRecording.pause()
        listener?.recordingPaused()
    }

    fun resumeRecording() {
        activeRecording.resume()
    }

    fun stopRecording() {
        activeRecording.stop()
    }

    private val videoRecordingListener = Consumer<VideoRecordEvent> { event ->
        when (event) {
            is VideoRecordEvent.Finalize -> {
                if (event.hasError()) {
                    listener?.onError(event.cause)
                } else {
                    listener?.recordingCompleted(event.outputResults.outputUri)
                }
            }
            is VideoRecordEvent.Pause -> {
                listener?.recordingPaused()
            }
            is VideoRecordEvent.Status -> {
                listener?.onProgress(event.recordingStats.recordedDurationNanos.fromNanoToSeconds())
            }
        }
    }

    interface Listener {
        fun onInitialised(cameraLensInfo: HashMap<Int, CameraInfo>)
        fun onProgress(progress: Int)
        fun recordingPaused()
        fun recordingCompleted(outputUri: Uri)
        fun onError(throwable: Throwable?)
        fun processFrame(bitmap: Bitmap?)
    }

    class Builder(val context: Context) {

        var lifecycleOwner: LifecycleOwner? = null
            private set

        fun registerLifecycleOwner(source: LifecycleOwner): Builder {
            this.lifecycleOwner = source
            return this
        }

        fun create(): VideoCaptureManager {
            requireNotNull(lifecycleOwner) { "Lifecycle owner is not set" }
            return VideoCaptureManager(this)
        }
    }

    private fun Long.fromNanoToSeconds() = (this / (1000 * 1000 * 1000)).toInt()
}

val LocalVideoCaptureManager =
    compositionLocalOf<VideoCaptureManager> { error("No capture manager found!") }