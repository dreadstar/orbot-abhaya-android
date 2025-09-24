package org.orbotabhaya.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton
import org.torproject.android.R
import org.orbotabhaya.task.TaskManager
import org.orbotabhaya.task.TaskRequest
import org.orbotabhaya.task.TaskProgress
import org.orbotabhaya.task.ServiceMetadata
import org.orbotabhaya.task.ServiceInput
import org.orbotabhaya.task.ServiceOutput
import android.widget.LinearLayout
import com.google.android.material.textfield.TextInputLayout

class TaskManagerFragment : Fragment() {
    private lateinit var folderPickerButton: MaterialButton
    private lateinit var folderNameEdit: TextInputEditText
    private var selectedFolder: String? = null
    private lateinit var imagePreview: android.widget.ImageView
    private val FILE_PICKER_REQUEST_CODE = 1001
    private val CAMERA_CAPTURE_REQUEST_CODE = 1002
    private var fileInputKey: String? = null
    private var fileInputUri: android.net.Uri? = null
    private var cameraImageUri: android.net.Uri? = null
    private lateinit var viewModel: TaskManagerViewModel
    private lateinit var serviceSearchInput: TextInputEditText
    private lateinit var searchButton: MaterialButton
    private lateinit var serviceResultsList: RecyclerView
    private lateinit var taskProgressList: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_task_manager, container, false)
        serviceSearchInput = view.findViewById(R.id.service_search_input)
        searchButton = view.findViewById(R.id.search_button)
        serviceResultsList = view.findViewById(R.id.service_results_list)
        taskProgressList = view.findViewById(R.id.task_progress_list)
        return view
    }

    private lateinit var inputFieldsContainer: ViewGroup
    private lateinit var submitTaskButton: MaterialButton
    private var selectedService: ServiceMetadata? = null
    private val inputViews = mutableMapOf<String, TextInputEditText>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[TaskManagerViewModel::class.java]

        serviceResultsList.layoutManager = LinearLayoutManager(context)
        taskProgressList.layoutManager = LinearLayoutManager(context)

        // Dynamically add input fields container, image preview, folder picker, and submit button
        inputFieldsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        (view as ViewGroup).addView(inputFieldsContainer)
        imagePreview = android.widget.ImageView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(200, 200)
            visibility = View.GONE
        }
        view.addView(imagePreview)
        folderPickerButton = MaterialButton(requireContext()).apply {
            text = "Select Destination Subfolder"
            visibility = View.GONE
        }
        view.addView(folderPickerButton)
        folderNameEdit = TextInputEditText(requireContext()).apply {
            hint = "Or create new subfolder name (optional)"
            visibility = View.GONE
        }
        view.addView(folderNameEdit)
        submitTaskButton = MaterialButton(requireContext()).apply {
            text = "Submit Task"
            visibility = View.GONE
        }
        view.addView(submitTaskButton)

        val serviceAdapter = ServiceResultsAdapter { metadata ->
            selectedService = metadata
            inputFieldsContainer.removeAllViews()
            inputViews.clear()
            var hasFileOutput = false
            // Show input fields for required inputs
            for (input in metadata.inputs) {
                    val inputLayout = TextInputLayout(requireContext())
                    val inputEdit = TextInputEditText(requireContext())
                inputLayout.hint = input.name
                inputLayout.addView(inputEdit)
                inputViews[input.name] = inputEdit
                // If input type is image/file, add both file picker and camera buttons
                if (input.type.contains("Bitmap", true) || input.type.contains("File", true) || input.type.contains("Image", true) || input.name.contains("Image", true)) {
                    val fileButton = MaterialButton(requireContext()).apply { text = "Select Image" }
                    fileButton.setOnClickListener {
                        fileInputKey = input.name
                        // Request storage permission if needed
                        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU &&
                            android.content.pm.PackageManager.PERMISSION_GRANTED !=
                            requireActivity().checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
                            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), FILE_PICKER_REQUEST_CODE)
                            return@setOnClickListener
                        }
                        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            type = "image/*"
                        }
                        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE)
                    }
                    inputLayout.addView(fileButton)

                    val cameraButton = MaterialButton(requireContext()).apply { text = "Take Photo" }
                    cameraButton.setOnClickListener {
                        fileInputKey = input.name
                        // Request camera permission if needed
                        if (android.content.pm.PackageManager.PERMISSION_GRANTED !=
                            requireActivity().checkSelfPermission(android.Manifest.permission.CAMERA)) {
                            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_CAPTURE_REQUEST_CODE)
                            return@setOnClickListener
                        }
                        val photoFile = java.io.File.createTempFile("photo_${System.currentTimeMillis()}", ".jpg", requireContext().cacheDir)
                        cameraImageUri = androidx.core.content.FileProvider.getUriForFile(
                            requireContext(),
                            requireContext().packageName + ".provider",
                            photoFile
                        )
                        val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraImageUri)
                        }
                        startActivityForResult(intent, CAMERA_CAPTURE_REQUEST_CODE)
                    }
                    inputLayout.addView(cameraButton)
                }
                    inputFieldsContainer.addView(inputLayout)
            }
            // If any output is a file, show folder picker
            for (output in metadata.outputs) {
                if (output.name.contains("File", true) || output.type.contains("File", true) || output.name.contains("Uri", true)) {
                    hasFileOutput = true
                    break
                }
            }
                if (hasFileOutput) {
                folderPickerButton.visibility = View.VISIBLE
                folderNameEdit.visibility = View.VISIBLE
                folderPickerButton.setOnClickListener {
                    // Show a dialog to navigate the Drop Folder and select a subfolder
                    // Use StorageDropFolderManager.getFolderContents() recursively for navigation
                    // When user selects a folder, set selectedFolder and update folderNameEdit
                    // TODO: Implement actual folder explorer dialog (see Drop Folder widget TODO below)
                }
    // === TODO: Drop Folder Widget Navigation/Expansion ===
    // The Drop Folder widget should be a navigable file explorer.
    // Use a RecyclerView with expandable/collapsible folder items (item_folder_content.xml).
    // When a folder is clicked, expand to show its contents (files and subfolders).
    // If a file download fails, gray out the file item and show a retry button within the explorer view.
                } else {
                folderPickerButton.visibility = View.GONE
                folderNameEdit.visibility = View.GONE
            }
            inputFieldsContainer.visibility = View.VISIBLE
            submitTaskButton.visibility = View.VISIBLE
        }
        serviceResultsList.adapter = serviceAdapter

        val progressAdapter = TaskProgressAdapter()
        taskProgressList.adapter = progressAdapter

        searchButton.setOnClickListener {
            val query = serviceSearchInput.text?.toString() ?: ""
            viewModel.searchServices(query)
        }

        submitTaskButton.setOnClickListener {
            val service = selectedService ?: return@setOnClickListener
            val params = mutableMapOf<String, Any>()
            for ((key, edit) in inputViews) {
                if ((key.contains("Bitmap", true) || key.contains("File", true) || key.contains("Image", true)) && fileInputKey == key) {
                    if (cameraImageUri != null) {
                        params[key] = cameraImageUri!!
                    } else if (fileInputUri != null) {
                        params[key] = fileInputUri!!
                    } else {
                        edit.error = "Required"
                        return@setOnClickListener
                    }
                } else {
                    val value = edit.text?.toString()
                    if (value.isNullOrBlank()) {
                        edit.error = "Required"
                        return@setOnClickListener
                    }
                    params[key] = value
                }
            }
            // Add destination folder if file output
            if (selectedFolder != null) {
                params["destinationFolder"] = selectedFolder!!
            } else if (folderNameEdit.visibility == View.VISIBLE && !folderNameEdit.text.isNullOrBlank()) {
                params["destinationFolder"] = folderNameEdit.text.toString()
            }
            viewModel.createTaskWithParams(service, params)
            inputFieldsContainer.visibility = View.GONE
            submitTaskButton.visibility = View.GONE
            fileInputKey = null
            fileInputUri = null
            cameraImageUri = null
            selectedFolder = null
        }

        // Observe LiveData
        viewModel.serviceResults.observe(viewLifecycleOwner) { results ->
            serviceAdapter.submitList(results)
        }
        viewModel.taskProgress.observe(viewLifecycleOwner) { progressList ->
            progressAdapter.submitList(progressList)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { uri ->
                fileInputUri = uri
                cameraImageUri = null
                fileInputKey?.let { key ->
                    inputViews[key]?.setText(uri.toString())
                }
                // Show image preview
                imagePreview.setImageURI(uri)
                imagePreview.visibility = android.view.View.VISIBLE
            }
        } else if (requestCode == CAMERA_CAPTURE_REQUEST_CODE && resultCode == android.app.Activity.RESULT_OK) {
            if (cameraImageUri != null) {
                fileInputUri = null
                fileInputKey?.let { key ->
                    inputViews[key]?.setText(cameraImageUri.toString())
                }
                // Show image preview
                imagePreview.setImageURI(cameraImageUri)
                imagePreview.visibility = android.view.View.VISIBLE
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (requestCode == FILE_PICKER_REQUEST_CODE) {
                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                startActivityForResult(intent, FILE_PICKER_REQUEST_CODE)
            } else if (requestCode == CAMERA_CAPTURE_REQUEST_CODE) {
                val photoFile = java.io.File.createTempFile("photo_${System.currentTimeMillis()}", ".jpg", requireContext().cacheDir)
                cameraImageUri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().packageName + ".provider",
                    photoFile
                )
                val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraImageUri)
                }
                startActivityForResult(intent, CAMERA_CAPTURE_REQUEST_CODE)
            }
        } else {
            // Permission denied, show error or toast
            android.widget.Toast.makeText(requireContext(), "Permission required", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

// ...existing code for adapters and viewmodel...
