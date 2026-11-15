package com.merrythieves.inkit.ui.activities

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.merrythieves.inkit.MainActivity
import com.merrythieves.inkit.R
import com.merrythieves.inkit.importer.ImportManager
import com.merrythieves.inkit.model.Notebook
import com.merrythieves.inkit.store.NotebookStore
import com.merrythieves.inkit.ui.adapters.NotebookAdapter
import java.util.UUID

/**
 * Activity for displaying and managing notebooks.
 * This is the main entry point for the app.
 */
class NotebookListActivity : AppCompatActivity() {
    
    private lateinit var notebookStore: NotebookStore
    private lateinit var notebookAdapter: NotebookAdapter
    private lateinit var recyclerNotebooks: androidx.recyclerview.widget.RecyclerView
    private lateinit var btnNewNotebook: FloatingActionButton
    private lateinit var btnSearch: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var emptyState: View
    
    // Import Manager for PDF and image import
    private lateinit var importManager: ImportManager
    private lateinit var importPdfLauncher: ActivityResultLauncher<Intent>
    private lateinit var importImageLauncher: ActivityResultLauncher<Intent>
    private lateinit var importMultipleLauncher: ActivityResultLauncher<Intent>
    
    companion object {
        private const val TAG = "NotebookListActivity"
        
        /**
         * Start this activity.
         */
        fun start(context: Context) {
            val intent = Intent(context, NotebookListActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            context.startActivity(intent)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notebook_list)
        
        notebookStore = NotebookStore.getInstance(this)
        
        // Check and perform migration if needed
        when (notebookStore.getMigrationState()) {
            NotebookStore.MigrationState.PENDING -> {
                Log.i(TAG, "Legacy data detected, performing migration")
                if (!notebookStore.migrateFromLegacy()) {
                    Toast.makeText(this, "Migration failed", Toast.LENGTH_LONG).show()
                }
            }
            NotebookStore.MigrationState.FAILED -> {
                Toast.makeText(this, "Migration failed previously", Toast.LENGTH_LONG).show()
            }
            else -> {
                Log.i(TAG, "No migration needed")
            }
        }
        
        // Initialize views
        recyclerNotebooks = findViewById(R.id.recyclerNotebooks)
        btnNewNotebook = findViewById(R.id.btnNewNotebook)
        btnSearch = findViewById(R.id.btnSearch)
        btnMore = findViewById(R.id.btnMore)
        emptyState = findViewById(R.id.emptyState)
        
        // Setup recycler view
        notebookAdapter = NotebookAdapter(
            context = this,
            notebooks = notebookStore.getAllNotebooksSorted(),
            onNotebookClick = { notebook -> openNotebook(notebook) },
            onNotebookMoreClick = { notebook, view -> showNotebookMenu(notebook, view) }
        )
        
        recyclerNotebooks.apply {
            layoutManager = LinearLayoutManager(this@NotebookListActivity)
            adapter = notebookAdapter
            setHasFixedSize(true)
        }
        
        // Register SAF launchers for import functionality
        importPdfLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            importManager.handleImportPdfResult(result)
        }
        importImageLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            importManager.handleImportImageResult(result)
        }
        importMultipleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            importManager.handleImportImagesResult(result)
        }
        
        // Initialize ImportManager for PDF and image import
        importManager = ImportManager(
            this, 
            importPdfLauncher, 
            importImageLauncher, 
            importMultipleLauncher,
            onImportComplete = { refreshNotebookList() }
        )
        importManager.initialize()
        
        // Setup click listeners
        btnNewNotebook.setOnClickListener { createNewNotebook() }
        btnSearch.setOnClickListener { showSearch() }
        btnMore.setOnClickListener { showListMenu() }
        
        // Update UI
        updateEmptyState()
        
        Log.i(TAG, "Activity created, ${notebookStore.getNotebookCount()} notebooks loaded")
    }
    
    override fun onResume() {
        super.onResume()
        // Force reload from store to ensure we have latest data
        notebookStore = NotebookStore.getInstance(this)
        refreshNotebookList()
    }
    
    /**
     * Refresh the notebook list from the store.
     */
    private fun refreshNotebookList() {
        notebookAdapter.updateNotebooks(notebookStore.getAllNotebooksSorted())
        updateEmptyState()
    }
    
    /**
     * Update empty state visibility.
     */
    private fun updateEmptyState() {
        val hasNotebooks = notebookStore.getNotebookCount() > 0
        recyclerNotebooks.visibility = if (hasNotebooks) View.VISIBLE else View.GONE
        emptyState.visibility = if (hasNotebooks) View.GONE else View.VISIBLE
    }
    
    /**
     * Open a notebook for viewing/editing.
     */
    private fun openNotebook(notebook: Notebook) {
        notebookStore.setCurrentNotebook(notebook.id)
        
        // Start MainActivity (which will be updated to PageActivity)
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("NOTEBOOK_ID", notebook.id)
        startActivity(intent)
        
        Log.i(TAG, "Opened notebook: ${notebook.id}")
    }
    
    /**
     * Create a new notebook.
     */
    private fun createNewNotebook() {
        val notebook = notebookStore.createNotebook(
            name = Notebook.DEFAULT_NAME,
            coverColor = Notebook.PREDEFINED_COLORS.random()
        )
        
        // Create a first page in the notebook
        notebookStore.createPage(notebook.id)
        
        // Open the new notebook
        openNotebook(notebook)
        
        Toast.makeText(this, "Created new notebook", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Created new notebook: ${notebook.id}")
    }
    
    /**
     * Show the notebook options menu.
     */
    private fun showNotebookMenu(notebook: Notebook, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Rename")
        popup.menu.add(0, 2, 1, "Duplicate")
        popup.menu.add(0, 3, 2, "Export")
        popup.menu.add(0, 4, 3, "Delete")
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showRenameDialog(notebook)
                2 -> duplicateNotebook(notebook)
                3 -> exportNotebook(notebook)
                4 -> showDeleteDialog(notebook)
            }
            true
        }
        
        popup.show()
    }
    
    /**
     * Show rename dialog for a notebook.
     */
    private fun showRenameDialog(notebook: Notebook) {
        val view = layoutInflater.inflate(R.layout.dialog_text_input, null)
        val editText = view.findViewById<android.widget.EditText>(R.id.editText)
        editText.setText(notebook.name)
        editText.selectAll()
        
        AlertDialog.Builder(this)
            .setTitle("Rename Notebook")
            .setView(view)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    notebookStore.renameNotebook(notebook.id, newName)
                    refreshNotebookList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Duplicate a notebook.
     */
    private fun duplicateNotebook(notebook: Notebook) {
        val newName = "${notebook.name} Copy"
        val newNotebook = notebookStore.createNotebook(
            name = newName,
            coverColor = notebook.coverColor
        )
        
        // Copy all pages
        for (pageId in notebook.pageIds) {
            val page = notebookStore.getPage(pageId)
            if (page != null) {
                // Create new page with same properties
                notebookStore.createPage(
                    notebookId = newNotebook.id,
                    backgroundType = page.backgroundType
                )
            }
        }
        
        refreshNotebookList()
        Toast.makeText(this, "Notebook duplicated", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Duplicated notebook: ${notebook.id} -> ${newNotebook.id}")
    }
    
    /**
     * Export a notebook.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun exportNotebook(notebook: Notebook) {
        Toast.makeText(this, "Export feature coming soon", Toast.LENGTH_SHORT).show()
        // TODO: Implement export in Phase 2
    }
    
    /**
     * Show delete confirmation dialog for a notebook.
     */
    private fun showDeleteDialog(notebook: Notebook) {
        AlertDialog.Builder(this)
            .setTitle("Delete Notebook")
            .setMessage("Delete '${notebook.name}' and all its pages? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deleteNotebook(notebook)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Delete a notebook.
     */
    private fun deleteNotebook(notebook: Notebook) {
        if (notebookStore.deleteNotebook(notebook.id)) {
            refreshNotebookList()
            Toast.makeText(this, "Notebook deleted", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "Deleted notebook: ${notebook.id}")
        } else {
            Toast.makeText(this, "Failed to delete notebook", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Show search functionality.
     * For now, shows a placeholder since full search isn't implemented.
     */
    private fun showSearch() {
        Toast.makeText(this, "Search functionality coming soon", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Search button clicked")
    }
    
    /**
     * Show menu for notebook list.
     */
    private fun showListMenu() {
        val popup = PopupMenu(this, btnMore)
        popup.menu.add(0, 1, 0, "Sort by name")
        popup.menu.add(0, 2, 1, "Sort by date")
        popup.menu.add(0, 3, 2, "-- Import --")
        popup.menu.add(0, 4, 3, "Import PDF")
        popup.menu.add(0, 5, 4, "Import image")
        popup.menu.add(0, 6, 5, "Import images (multiple)")
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    Toast.makeText(this, "Sort by name", Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    Toast.makeText(this, "Sort by date", Toast.LENGTH_SHORT).show()
                    true
                }
                4 -> {
                    importManager.importPdf()
                    true
                }
                5 -> {
                    importManager.importImage()
                    true
                }
                6 -> {
                    importManager.importImages()
                    true
                }
                else -> false
            }
        }
        
        popup.show()
        Log.i(TAG, "List menu shown")
    }
}
