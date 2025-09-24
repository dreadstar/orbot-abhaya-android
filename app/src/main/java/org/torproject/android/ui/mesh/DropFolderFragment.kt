package org.torproject.android.ui.mesh

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.torproject.android.R
import org.torproject.android.service.storage.StorageDropFolderManager
import org.torproject.android.ui.mesh.model.StorageItem

class DropFolderFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DropFolderAdapter
    private lateinit var storageManager: StorageDropFolderManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_drop_folder, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.dropFolderRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        storageManager = StorageDropFolderManager.getInstance(requireContext())
        adapter = DropFolderAdapter { item -> onShareClicked(item) }
        recyclerView.adapter = adapter
        loadFolderContents()
    }

    private fun loadFolderContents() {
        val items = storageManager.getFolderContents()
        adapter.submitList(items)
    }

    private fun onShareClicked(item: StorageItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_drop_folder_share, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        val friendButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.shareWithFriendButton)
        val everyoneButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.shareWithEveryoneButton)
        val taskButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.shareWithTaskButton)
        val triggerButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.shareAsTaskTriggerButton)

        // Only show Task Trigger for subfolders
        triggerButton.visibility = if (item.isDirectory) View.VISIBLE else View.GONE

        friendButton.setOnClickListener {
            dialog.dismiss()
            showShareWithFriendDialog(item)
        }

        everyoneButton.setOnClickListener {
            dialog.dismiss()
            shareWithEveryone(item)
        }

        taskButton.setOnClickListener {
            dialog.dismiss()
            showShareWithTaskDialog(item)
        }

        triggerButton.setOnClickListener {
            dialog.dismiss()
            showTaskTriggerDialog(item)
        }

        dialog.show()
    }

    private fun showShareWithFriendDialog(item: StorageItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_drop_folder_share_with_friend, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.friendListRecyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())

        val friendList: List<org.torproject.android.ui.mesh.model.FriendContact> = getFriendContactList()
        val adapter = FriendListAdapter { selectedFriend ->
            dialog.dismiss()
            // Integrate with backend: share item with selected friend
            val success = storageManager.shareItem(item, setOf(selectedFriend.id))
            if (success) loadFolderContents()
        }
        recyclerView.adapter = adapter
        adapter.submitList(friendList)

        dialog.show()
    }

    private fun getFriendContactList(): List<org.torproject.android.ui.mesh.model.FriendContact> {
        // TODO: Integrate with actual contacts or mesh friend list
        return listOf(
            org.torproject.android.ui.mesh.model.FriendContact("1", "Alice", true),
            org.torproject.android.ui.mesh.model.FriendContact("2", "Bob", false)
        )
    }

    private fun shareWithEveryone(item: StorageItem) {
        // Integrate with backend: share item with everyone
        val success = storageManager.shareItem(item, setOf("everyone"))
        if (success) loadFolderContents()
    }

    private fun stopSharingItem(item: StorageItem) {
        val success = storageManager.stopSharingItem(item)
        if (success) loadFolderContents()
    }

    private fun showShareWithTaskDialog(item: StorageItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_drop_folder_share_with_task, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.taskListRecyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())

        val cachedServiceList: List<org.torproject.android.service.compute.ServiceSearchResult> = getCachedServiceList()
        val adapter = TaskListAdapter { selectedTask ->
            dialog.dismiss()
            // Wire up backend: create and launch new task with item (or all items if folder)
            val params = mutableMapOf<String, Any>()
            val inputName = selectedTask.manifest.files.firstOrNull() ?: "input"
            if (item.isDirectory) {
                // Add all items in folder as input
                val folderItems = storageManager.getFolderContents().filter { it.path.startsWith(item.path) }
                params[inputName] = folderItems.map { it.path }
            } else {
                params[inputName] = item.path
            }
            org.torproject.android.service.compute.TaskManager.createTaskWithParams(
                service = toServiceMeta(selectedTask),
                params = params
            )
        }
        recyclerView.adapter = adapter
        adapter.submitList(cachedServiceList)

        dialog.show()
    }

    private fun toServiceMeta(result: org.torproject.android.service.compute.ServiceSearchResult): org.torproject.android.service.compute.ServiceMeta {
        // Convert ServiceSearchResult to ServiceMeta for TaskManager
        val inputs = result.manifest.files.map { filename ->
            org.torproject.android.service.compute.ServiceInput(name = filename, type = "File", required = true)
        }
        val outputs = result.manifest.files.map { filename ->
            org.torproject.android.service.compute.ServiceOutput(name = filename, type = "File")
        }
        return org.torproject.android.service.compute.ServiceMeta(
            serviceId = result.serviceId,
            manifest = result.manifest,
            executionProfile = result.executionProfile,
            inputs = inputs,
            outputs = outputs,
            capabilities = result.capabilities
        )
    }

    private fun getCachedServiceList(): List<org.torproject.android.service.compute.ServiceSearchResult> {
        // TODO: Integrate with actual cache or ViewModel
        return emptyList() // Placeholder
    }

    private fun showTaskTriggerDialog(item: StorageItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_drop_folder_task_trigger, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.taskTriggerListRecyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        val inputSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.taskInputSpinner)
        val setupButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.setupTaskTriggerButton)

        val cachedServiceList: List<org.torproject.android.service.compute.ServiceSearchResult> = getCachedServiceList()
        val adapter = TaskListAdapter { selectedTask ->
            val inputNames = selectedTask.manifest.files
            val spinnerAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, inputNames)
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            inputSpinner.adapter = spinnerAdapter
            inputSpinner.setSelection(0)
            inputSpinner.tag = selectedTask
        }
        recyclerView.adapter = adapter
        adapter.submitList(cachedServiceList)

        setupButton.setOnClickListener {
            val selectedTask = inputSpinner.tag as? org.torproject.android.service.compute.ServiceSearchResult
            val selectedInput = inputSpinner.selectedItem as? String
            if (selectedTask != null && selectedInput != null) {
                dialog.dismiss()
                // Wire up backend: setup trigger for new file in subfolder
                setupTaskTrigger(item, selectedTask, selectedInput)
            }
        }

        dialog.show()
    }

    private var fileObserver: android.os.FileObserver? = null

    private fun setupTaskTrigger(
        folder: StorageItem,
        task: org.torproject.android.service.compute.ServiceSearchResult,
        inputName: String
    ) {
        // Remove previous observer if any
        fileObserver?.stopWatching()

        val folderPath = folder.path
        fileObserver = object : android.os.FileObserver(folderPath, android.os.FileObserver.CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (event == android.os.FileObserver.CREATE && path != null) {
                    val newFilePath = if (folderPath.endsWith("/")) folderPath + path else "$folderPath/$path"
                    val params = mutableMapOf<String, Any>()
                    params[inputName] = newFilePath
                    org.torproject.android.service.compute.TaskManager.createTaskWithParams(
                        service = toServiceMeta(task),
                        params = params
                    )
                }
            }
        }
        fileObserver?.startWatching()
    }
}
