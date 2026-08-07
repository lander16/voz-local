package dev.sebastian.vozlocal.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.sebastian.vozlocal.audio.AudioRecorder
import dev.sebastian.vozlocal.data.repository.DictationRepository

class MainViewModelFactory(
    private val repository: DictationRepository,
    private val audioRecorder: AudioRecorder
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository, audioRecorder) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
