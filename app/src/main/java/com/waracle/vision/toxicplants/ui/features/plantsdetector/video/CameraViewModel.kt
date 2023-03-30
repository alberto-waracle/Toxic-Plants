@file:OptIn(ExperimentalPermissionsApi::class)

package com.waracle.vision.toxicplants.ui.features.plantsdetector.video

import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.TorchState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.PermissionState
import com.waracle.vision.toxicplants.R
import com.waracle.vision.toxicplants.plantdetector.PlantDetector
import com.waracle.vision.toxicplants.rotate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val TAG = "RecordingViewModel"

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val fileManager: FileManager,
    val permissionsHandler: PermissionsHandler,
    val plantDetector: PlantDetector
) : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _effect = MutableSharedFlow<Effect>()
    val effect: SharedFlow<Effect> = _effect

    private val _permissionMessage = MutableStateFlow("Waiting")
    val permissionMessage: StateFlow<String> = _permissionMessage

    init {
        permissionsHandler
            .state
            .onEach { handlerState ->
                _state.update { it.copy(multiplePermissionsState = handlerState.multiplePermissionsState) }
            }
            .catch {
                viewModelScope.launch {
                    _permissionMessage.emit(it.message ?: "Permission exception")
                    Timber.e(it, it.message)
                }
            }
            .launchIn(viewModelScope)
    }

    fun onEvent(event: Event) {
        when (event) {
            Event.FlashTapped -> onFlashTapped()
            Event.CloseTapped -> onCloseTapped()
            Event.FlipTapped -> onFlipTapped()

            Event.RecordTapped -> onRecordTapped()
            Event.PictureTapped -> onPictureTapped()
            Event.PauseTapped -> onPauseTapped()
            Event.ResumeTapped -> onResumeTapped()
            Event.StopTapped -> onStopTapped()

            is Event.CameraInitialized -> onCameraInitialized(event.cameraLensInfo)
            is Event.OnProgress -> onProgress(event.progress)
            is Event.RecordingPaused -> onPaused()
            is Event.RecordingEnded -> onRecordingFinished(event.outputUri)
            is Event.Error -> onError()

            Event.PermissionRequired -> onPermissionRequired()
        }
    }

    private fun onFlashTapped() {
        _state.update {
            when (_state.value.torchState) {
                TorchState.OFF -> it.copy(torchState = TorchState.ON)
                TorchState.ON -> it.copy(torchState = TorchState.OFF)
                else -> it.copy(torchState = TorchState.OFF)
            }
        }
    }

    private fun onCloseTapped() = viewModelScope.launch {
        _effect.emit(Effect.NavigateBack)
    }

    private fun onFlipTapped() {
        val lens = if (_state.value.lens == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        //Check if the lens has flash unit
        val flashMode = if (_state.value.lensInfo[lens]?.hasFlashUnit() == true) {
            _state.value.flashMode
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
        if (_state.value.lensInfo[lens] != null) {
            _state.update { it.copy(lens = lens, flashMode = flashMode) }
        }
    }

    private fun onPermissionRequired() {
        permissionsHandler.onEvent(PermissionsHandler.Event.PermissionRequired)
    }

    private fun onPauseTapped() = viewModelScope.launch {
        _effect.emit(Effect.PauseRecording)
    }


    private fun onResumeTapped() = viewModelScope.launch {
        _effect.emit(Effect.ResumeRecording)
    }


    private fun onStopTapped() = viewModelScope.launch {
        _effect.emit(Effect.StopRecording)
    }


    private fun onRecordTapped() = viewModelScope.launch {
        try {
            val filePath = fileManager.createFile("videos", "mp4")
            _effect.emit(Effect.RecordVideo(filePath))
        } catch (exception: IllegalArgumentException) {
            Timber.e(exception, exception.message)
            _effect.emit(Effect.ShowMessage())
        }
    }

    private fun onPictureTapped() = viewModelScope.launch {
        _effect.emit(Effect.SavePicture)
    }


    private fun onRecordingFinished(uri: Uri) {
        Timber.w("onRecordingFinished - NO navigation")
        _state.update { it.copy(recordingStatus = RecordingStatus.Idle, recordedLength = 0) }
    }

    private fun onError() {
        _state.update { it.copy(recordedLength = 0, recordingStatus = RecordingStatus.Idle) }
        viewModelScope.launch {
            _effect.emit(Effect.ShowMessage())
        }
    }

    private fun onPaused() {
        _state.update { it.copy(recordingStatus = RecordingStatus.Paused) }
    }

    private fun onProgress(progress: Int) {
        _state.update {
            it.copy(
                recordedLength = progress,
                recordingStatus = RecordingStatus.InProgress
            )
        }
    }

    private fun onCameraInitialized(cameraLensInfo: HashMap<Int, CameraInfo>) {
        if (cameraLensInfo.isNotEmpty()) {
            val defaultLens = if (cameraLensInfo[CameraSelector.LENS_FACING_BACK] != null) {
                CameraSelector.LENS_FACING_BACK
            } else if (cameraLensInfo[CameraSelector.LENS_FACING_BACK] != null) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                null
            }
            _state.update {
                it.copy(
                    lens = it.lens ?: defaultLens,
                    lensInfo = cameraLensInfo
                )
            }
        }
    }

    fun analiseImage(bitmap: Bitmap) = viewModelScope.launch {
        _permissionMessage.emit(plantDetector.processImage(bitmap.rotate()))
    }


    data class State(
        val lens: Int? = null,
        @TorchState.State val torchState: Int = TorchState.OFF,
        @ImageCapture.FlashMode val flashMode: Int = ImageCapture.FLASH_MODE_OFF,
        val multiplePermissionsState: MultiplePermissionsState? = null,
        val lensInfo: MutableMap<Int, CameraInfo> = mutableMapOf(),
        val recordedLength: Int = 0,
        val recordingStatus: RecordingStatus = RecordingStatus.Idle,
        val permissionRequestInFlight: Boolean = false,
        val hasCameraPermission: Boolean = false,
        val permissionState: PermissionState? = null,
        val permissionAction: PermissionsHandler.Action = PermissionsHandler.Action.NO_ACTION
    )

    sealed class Event {
        data class CameraInitialized(val cameraLensInfo: HashMap<Int, CameraInfo>) : Event()

        data class OnProgress(val progress: Int) : Event()
        object RecordingPaused : Event()
        data class RecordingEnded(val outputUri: Uri) : Event()
        data class Error(val throwable: Throwable?) : Event()

        object FlashTapped : Event()
        object CloseTapped : Event()
        object FlipTapped : Event()

        object RecordTapped : Event()
        object PictureTapped : Event()
        object PauseTapped : Event()
        object ResumeTapped : Event()
        object StopTapped : Event()
        object PermissionRequired : Event()
    }

    sealed class Effect {
        data class ShowMessage(val message: Int = R.string.something_went_wrong) : Effect()
        data class RecordVideo(val filePath: String) : Effect()
        object SavePicture : Effect()
        object NavigateBack : Effect()

        object PauseRecording : Effect()
        object ResumeRecording : Effect()
        object StopRecording : Effect()
    }

    sealed class RecordingStatus {
        object Idle : RecordingStatus()
        object InProgress : RecordingStatus()
        object Paused : RecordingStatus()
    }
}