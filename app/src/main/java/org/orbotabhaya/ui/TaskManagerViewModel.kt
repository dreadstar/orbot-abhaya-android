package org.orbotabhaya.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
// All org.orbotabhaya.task.* imports removed; only simulated data used

class TaskManagerViewModel : ViewModel() {
    // Simulated service results and progress
    private val _serviceResults = MutableLiveData<List<String>>()
    val serviceResults: LiveData<List<String>> = _serviceResults
    private val _taskProgress = MutableLiveData<List<String>>()
    val taskProgress: LiveData<List<String>> = _taskProgress

    fun searchServices(query: String) {
        // Simulate search: filter static list
        val allServices = listOf("Image Classification", "Text Translation", "File Upload", "Document OCR")
        val results = if (query.isBlank()) allServices else allServices.filter { it.contains(query, true) }
        _serviceResults.postValue(results)
    }

    fun createTask(serviceName: String) {
        // Simulate task creation
        updateProgress(serviceName)
    }

    fun createTaskWithParams(serviceName: String, params: Map<String, Any>) {
        // Simulate task creation with params
        updateProgress(serviceName)
    }

    fun updateProgress(serviceName: String) {
        // Simulate progress
        _taskProgress.postValue(listOf("Task for $serviceName: Complete"))
    }
}
