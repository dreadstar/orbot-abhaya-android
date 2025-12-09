package org.orbotabhaya.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton
import org.torproject.android.R
// All org.orbotabhaya.task.* imports removed; only simulated data used
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

    // Activity Result API launchers
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>
    private lateinit var takePhotoLauncher: ActivityResultLauncher<android.net.Uri?>
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    // Removed: private lateinit var viewModel: TaskManagerViewModel
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
    private var selectedServiceIndex: Int? = null
    private val inputViews = mutableMapOf<String, TextInputEditText>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Register Activity Result API handlers (simulated, not used)
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { }
        takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { }
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

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

        // Simulated service data
        val simulatedServices = listOf(
            "Image Classification",
            "Text Translation",
            "File Upload",
            "Document OCR"
        )
        val serviceAdapter = object : RecyclerView.Adapter<SimServiceViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SimServiceViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
                return SimServiceViewHolder(view)
            }
            override fun getItemCount(): Int = simulatedServices.size
            override fun onBindViewHolder(holder: SimServiceViewHolder, position: Int) {
                holder.textView.text = simulatedServices[position]
                holder.itemView.setOnClickListener {
                    selectedServiceIndex = position
                    inputFieldsContainer.removeAllViews()
                    inputViews.clear()
                    // Simulate input fields for each service
                    val inputLayout = TextInputLayout(requireContext())
                    val inputEdit = TextInputEditText(requireContext())
                    inputLayout.hint = "Input for ${simulatedServices[position]}"
                    inputLayout.addView(inputEdit)
                    inputViews[simulatedServices[position]] = inputEdit
                    inputFieldsContainer.addView(inputLayout)
                    inputFieldsContainer.visibility = View.VISIBLE
                    submitTaskButton.visibility = View.VISIBLE
                }
            }
        }
        serviceResultsList.adapter = serviceAdapter

        // Simulated progress adapter (empty)
        taskProgressList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }
            override fun getItemCount(): Int = 0
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        }

        searchButton.setOnClickListener {
            // Simulate search: no-op, as simulatedServices is static
        }

        submitTaskButton.setOnClickListener {
            // Simulate task submission
            inputFieldsContainer.visibility = View.GONE
            submitTaskButton.visibility = View.GONE
            fileInputKey = null
            fileInputUri = null
            cameraImageUri = null
            selectedFolder = null
        }
    }

    // Duplicate onViewCreated removed — registrations are already present in the main onViewCreated above.
}

class SimServiceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val textView: android.widget.TextView = itemView.findViewById(android.R.id.text1)
}
// No adapters or viewmodel from org.orbotabhaya.task.* remain; all logic is simulated.
