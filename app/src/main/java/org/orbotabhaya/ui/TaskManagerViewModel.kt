package org.orbotabhaya.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.orbotabhaya.task.TaskManager
import org.orbotabhaya.task.TaskProgress
import org.orbotabhaya.task.ServiceMetadata

class TaskManagerViewModel : ViewModel() {
    private val taskManager = TaskManager.getInstance()
    private val _serviceResults = MutableLiveData<List<ServiceMetadata>>()
    val serviceResults: LiveData<List<ServiceMetadata>> = _serviceResults
    private val _taskProgress = MutableLiveData<List<TaskProgress>>()
    val taskProgress: LiveData<List<TaskProgress>> = _taskProgress

    fun searchServices(query: String) {
        val results = taskManager.searchServices(query)
        _serviceResults.postValue(results)
    }

    fun createTask(metadata: ServiceMetadata) {
        val taskId = taskManager.createTask(metadata)
        updateProgress()
    }

    fun createTaskWithParams(metadata: ServiceMetadata, params: Map<String, Any>) {
        val taskId = taskManager.createTaskWithParams(metadata, params)
        updateProgress()
    }

    fun updateProgress() {
        val progressList = taskManager.getTaskProgress()
        _taskProgress.postValue(progressList)
    }
}
