package org.torproject.android.ui.mesh

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.torproject.android.R
import com.ustadmobile.meshrabiya.api.MeshrabiyaApi
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import org.torproject.android.ui.mesh.model.StorageItem
import org.torproject.android.ui.mesh.SimulatedServiceTask

class DropFolderFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DropFolderAdapter
    private lateinit var meshrabiyaApi: MeshrabiyaApi

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
        meshrabiyaApi = MeshrabiyaApiImpl.getInstance()
        adapter = DropFolderAdapter(
            onShareClicked = { item -> onShareClicked(item) },
            meshrabiyaApi = meshrabiyaApi,
            folderId = "drop" // TODO: Use actual folder id/path if needed
        )
        recyclerView.adapter = adapter
        loadFolderContents()
    }

    private fun loadFolderContents() {
        val files = meshrabiyaApi.getDropFolderFiles()
        // Use universal conversion helper
        adapter.submitList(files.map { org.torproject.android.ui.mesh.model.StorageItem(
            name = it.name,
            path = it.absolutePath,
            isDirectory = it.isDirectory,
            size = if (it.isFile) it.length() else 0L,
            lastModified = it.lastModified(),
            isShared = false,
            sharedWith = emptySet()
        ) })
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
        val adapter = FriendListAdapter { friend ->
            dialog.dismiss()
            // Integrate with backend: share item with selected friend using MeshrabiyaApi
            // Simulate sharing: show a toast and reload contents
            android.widget.Toast.makeText(requireContext(), "Shared '${item.name}' with ${friend.displayName}", android.widget.Toast.LENGTH_SHORT).show()
            loadFolderContents()
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
        // Integrate with backend: share item with everyone using MeshrabiyaApi
        // Simulate sharing: show a toast and reload contents
        android.widget.Toast.makeText(requireContext(), "Shared '${item.name}' with everyone", android.widget.Toast.LENGTH_SHORT).show()
        loadFolderContents()
    }

    private fun stopSharingItem(item: StorageItem) {
        // Simulate stop sharing: show a toast and reload contents
        android.widget.Toast.makeText(requireContext(), "Stopped sharing '${item.name}'", android.widget.Toast.LENGTH_SHORT).show()
        loadFolderContents()
    }

    private fun showShareWithTaskDialog(item: StorageItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_drop_folder_share_with_task, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.taskListRecyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())

        // Simulated service/task list
        val cachedServiceList = listOf(
            SimulatedServiceTask("id1", "Task 1", "Alice", "ML"),
            SimulatedServiceTask("id2", "Task 2", "Bob", "Data Processing")
        )
        val adapter = TaskListAdapter { selectedTask ->
            dialog.dismiss()
            val params = mutableMapOf<String, Any>()
            val inputName = "input" // Simulated input name
            if (item.isDirectory) {
                val folderItems = meshrabiyaApi.getDropFolderFiles().filter { it.path.startsWith(item.path) }
                params[inputName] = folderItems.map { it.absolutePath }
            } else {
                params[inputName] = item.path
            }
            // Simulate task creation
            android.widget.Toast.makeText(requireContext(), "Task '${selectedTask.serviceName}' started with input(s): ${params[inputName]}", android.widget.Toast.LENGTH_SHORT).show()
        }
        recyclerView.adapter = adapter
        adapter.submitList(cachedServiceList)

        dialog.show()
    }

    // Removed all references to real compute objects; simulation only

    private fun showTaskTriggerDialog(item: StorageItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_drop_folder_task_trigger, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.taskTriggerListRecyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        val inputSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.taskInputSpinner)
        val setupButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.setupTaskTriggerButton)

        val cachedServiceList = listOf(
            SimulatedServiceTask("id1", "Task 1", "Alice", "ML"),
            SimulatedServiceTask("id2", "Task 2", "Bob", "Data Processing")
        )
        val adapter = TaskListAdapter { selectedTask ->
            val inputNames = listOf("input") // Simulated input names
            val spinnerAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, inputNames)
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            inputSpinner.adapter = spinnerAdapter
            inputSpinner.setSelection(0)
            inputSpinner.tag = selectedTask
        }
        recyclerView.adapter = adapter
        adapter.submitList(cachedServiceList)

        setupButton.setOnClickListener {
            val selectedTask = inputSpinner.tag as? SimulatedServiceTask
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
        task: SimulatedServiceTask,
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
                    // Simulate task trigger
                    android.widget.Toast.makeText(requireContext(), "Task '${task.serviceName}' triggered for file: $newFilePath", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        fileObserver?.startWatching()
    }
}

// Simulated service/task class for UI logic (top-level for full file access)
// Only one declaration allowed; remove any duplicates above