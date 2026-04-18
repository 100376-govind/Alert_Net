package com.alertnet.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.alertnet.app.AlertNetApplication
import com.alertnet.app.mesh.MeshManager

/**
 * Factory for creating ViewModels with the MeshManager dependency.
 * Provides manual dependency injection without Hilt/Dagger.
 */
class ViewModelFactory(
    private val app: AlertNetApplication
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> {
                ChatViewModel(app.meshManager, app.deviceId) as T
            }
            modelClass.isAssignableFrom(PeersViewModel::class.java) -> {
                PeersViewModel(app.meshManager) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
