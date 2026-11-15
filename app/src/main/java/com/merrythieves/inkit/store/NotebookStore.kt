package com.merrythieves.inkit.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.merrythieves.inkit.CanvasStore
import com.merrythieves.inkit.model.Notebook
import com.merrythieves.inkit.model.Page
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Store for managing notebooks and pages.
 * 
 * This is the primary data store for inkit, replacing the old CanvasStore.
 * It provides hierarchical organization with notebooks containing pages,
 * and includes migration support from the legacy flat page structure.
 * 
 * Storage structure:
 *   filesDir/notebooks/
 *     ├── notebooks.json          # List of all notebooks
 *     └── <notebookId>/
 *         ├── meta.json           # Notebook metadata
 *         └── pages/
 *             ├── <pageId>.png    # Page content (ink strokes)
 *             └── meta.json       # Page metadata
 */
class NotebookStore private constructor(private val context: Context) {
    
    private val notebooksDir: File = File(context.filesDir, "notebooks").apply { mkdirs() }
    private val notebooksIndexFile: File = File(notebooksDir, "notebooks.json")
    private val legacyDir: File = File(context.filesDir, "canvases")
    
    /** List of all notebooks, loaded lazily */
    private val _notebooks: MutableList<Notebook> = mutableListOf()
    val notebooks: List<Notebook> get() = _notebooks.toList()
    
    /** Map of notebook ID to notebook for quick lookup */
    private val notebookMap: MutableMap<String, Notebook> = mutableMapOf()
    
    /** Map of page ID to page for quick lookup */
    private val pageMap: MutableMap<String, Page> = mutableMapOf()
    
    /** Current notebook ID, null if no notebook is selected */
    private var _currentNotebookId: String? = null
    
    /** Current page ID, null if no page is selected */
    private var _currentPageId: String? = null
    
    /** Whether the store has been initialized */
    private var isInitialized = false
    
    /** Migration state */
    enum class MigrationState {
        NOT_NEEDED,    // No legacy data
        PENDING,       // Legacy data exists but not migrated
        COMPLETED,     // Migration completed
        FAILED         // Migration failed
    }
    
    init {
        load()
    }
    
    /**
     * Get the current notebook.
     */
    fun getCurrentNotebook(): Notebook? {
        ensureInitialized()
        return _currentNotebookId?.let { notebookMap[it] }
    }
    
    /**
     * Get the current notebook ID.
     */
    fun getCurrentNotebookId(): String? {
        ensureInitialized()
        return _currentNotebookId
    }
    
    /**
     * Get the current page.
     */
    fun getCurrentPage(): Page? {
        ensureInitialized()
        return _currentPageId?.let { pageMap[it] }
    }
    
    /**
     * Get the current page ID.
     */
    fun getCurrentPageId(): String? {
        ensureInitialized()
        return _currentPageId
    }
    
    /**
     * Get a notebook by ID.
     */
    fun getNotebook(notebookId: String): Notebook? {
        ensureInitialized()
        return notebookMap[notebookId]
    }
    
    /**
     * Get a page by ID.
     */
    fun getPage(pageId: String): Page? {
        ensureInitialized()
        return pageMap[pageId]
    }
    
    /**
     * Get all pages in a notebook.
     */
    fun getPagesInNotebook(notebookId: String): List<Page> {
        ensureInitialized()
        val notebook = notebookMap[notebookId] ?: return emptyList()
        return notebook.pageIds.mapNotNull { pageMap[it] }
    }
    
    /**
     * Get the number of pages in a notebook.
     */
    fun getPageCount(notebookId: String): Int {
        ensureInitialized()
        return notebookMap[notebookId]?.pageIds?.size ?: 0
    }
    
    /**
     * Get the index of a page within its notebook.
     */
    fun getPageIndex(pageId: String): Int {
        ensureInitialized()
        val page = pageMap[pageId] ?: return -1
        val notebook = notebookMap[page.notebookId] ?: return -1
        return notebook.pageIds.indexOf(pageId)
    }
    
    /**
     * Get the next page in the current notebook, or null if at the end.
     */
    fun getNextPage(): Page? {
        ensureInitialized()
        val currentPage = _currentPageId?.let { pageMap[it] } ?: return null
        val notebook = notebookMap[currentPage.notebookId] ?: return null
        val currentIndex = notebook.pageIds.indexOf(currentPage.id)
        if (currentIndex < 0 || currentIndex >= notebook.pageIds.size - 1) return null
        return pageMap[notebook.pageIds[currentIndex + 1]]
    }
    
    /**
     * Get the previous page in the current notebook, or null if at the beginning.
     */
    fun getPreviousPage(): Page? {
        ensureInitialized()
        val currentPage = _currentPageId?.let { pageMap[it] } ?: return null
        val notebook = notebookMap[currentPage.notebookId] ?: return null
        val currentIndex = notebook.pageIds.indexOf(currentPage.id)
        if (currentIndex <= 0) return null
        return pageMap[notebook.pageIds[currentIndex - 1]]
    }
    
    /**
     * Load all notebooks and pages from storage.
     */
    private fun load() {
        if (isInitialized) return
        
        Log.i(TAG, "Loading notebooks from: ${notebooksDir.absolutePath}")
        
        // Check if migration is needed
        checkMigrationNeeded()
        
        // Load notebooks index
        loadNotebooksIndex()
        
        // Load individual notebook metadata
        loadNotebookMetadata()
        
        // Load page metadata
        loadPageMetadata()
        
        // Set current notebook and page if not set
        if (_currentNotebookId == null && _notebooks.isNotEmpty()) {
            _currentNotebookId = _notebooks[0].id
            val firstPage = _notebooks[0].pageIds.firstOrNull()
            if (firstPage != null) {
                _currentPageId = firstPage
            }
        }
        
        isInitialized = true
        Log.i(TAG, "Loaded ${_notebooks.size} notebooks, ${pageMap.size} pages")
    }
    
    /**
     * Check if migration from legacy CanvasStore is needed.
     */
    private fun checkMigrationNeeded(): MigrationState {
        if (!legacyDir.exists() || legacyDir.listFiles().isNullOrEmpty()) {
            return MigrationState.NOT_NEEDED
        }
        
        // Check if notebooks.json exists (migration already done)
        if (notebooksIndexFile.exists()) {
            return MigrationState.COMPLETED
        }
        
        return MigrationState.PENDING
    }
    
    /**
     * Migrate from legacy CanvasStore format to new NotebookStore format.
     * This creates a single default notebook containing all existing pages.
     */
    fun migrateFromLegacy(): Boolean {
        Log.i(TAG, "Starting migration from legacy CanvasStore")
        
        if (!legacyDir.exists() || legacyDir.listFiles().isNullOrEmpty()) {
            Log.i(TAG, "No legacy data to migrate")
            return true
        }
        
        if (notebooksIndexFile.exists()) {
            Log.w(TAG, "Notebooks index already exists, skipping migration")
            return true
        }
        
        return try {
            // Create a default notebook
            val defaultNotebook = Notebook(
                id = DEFAULT_NOTEBOOK_ID,
                name = "My Notes",
                sortOrder = 0
            )
            
            // Load legacy index
            val legacyIndexFile = File(legacyDir, "index.json")
            val legacyStore = if (legacyIndexFile.exists()) {
                CanvasStore(context)
            } else {
                null
            }
            
            // Create pages from legacy canvases
            val legacyFiles = legacyDir.listFiles()?.filter { 
                it.isFile && it.name.endsWith(".png") && !it.name.endsWith(".tmp")
            } ?: emptyList()
            
            Log.i(TAG, "Found ${legacyFiles.size} legacy canvas files")
            
            for ((index, file) in legacyFiles.withIndex()) {
                val pageId = UUID.randomUUID().toString()
                
                // Get metadata from legacy store if available
                val legacyMeta = legacyStore?.getMeta(index)
                val backgroundType = legacyMeta?.backgroundType ?: 0
                
                val page = Page.fromLegacy(
                    notebookId = defaultNotebook.id,
                    legacyBackgroundType = backgroundType
                )
                
                defaultNotebook.addPageId(pageId)
                
                // Copy the PNG file to the new location
                val pageDir = File(File(notebooksDir, defaultNotebook.id), "pages").apply { mkdirs() }
                val newFile = File(pageDir, "$pageId.png")
                file.copyTo(newFile, overwrite = true)
                
                // Save page metadata
                savePageMetadata(page.copy(id = pageId))
                
                pageMap[pageId] = page.copy(id = pageId)
            }
            
            // Save notebook metadata and index
            _notebooks.add(defaultNotebook)
            notebookMap[defaultNotebook.id] = defaultNotebook
            
            saveNotebookMetadata(defaultNotebook)
            saveNotebooksIndex()
            
            // Set current selection
            _currentNotebookId = defaultNotebook.id
            if (defaultNotebook.pageIds.isNotEmpty()) {
                _currentPageId = defaultNotebook.pageIds[0]
            }
            
            Log.i(TAG, "Migration completed: ${legacyFiles.size} pages migrated to 1 notebook")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Migration failed: ${t.message}", t)
            false
        }
    }
    
    /**
     * Load notebooks from the index file.
     */
    private fun loadNotebooksIndex() {
        if (!notebooksIndexFile.exists()) {
            _notebooks.clear()
            return
        }
        
        try {
            val json = JSONObject(notebooksIndexFile.readText())
            val array = json.optJSONArray("notebooks") ?: JSONArray()
            
            _notebooks.clear()
            notebookMap.clear()
            
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val notebook = Notebook(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    createdAt = obj.getLong("createdAt"),
                    updatedAt = obj.getLong("updatedAt"),
                    pageIds = obj.optJSONArray("pageIds")?.let { arr ->
                        val list = mutableListOf<String>()
                        for (j in 0 until arr.length()) {
                            list.add(arr.getString(j))
                        }
                        list
                    } ?: mutableListOf(),
                    coverColor = obj.optInt("coverColor", -1).takeIf { it != -1 },
                    isLocked = obj.optBoolean("isLocked", false),
                    sortOrder = obj.optInt("sortOrder", 0),
                    isFavorite = obj.optBoolean("isFavorite", false)
                )
                
                _notebooks.add(notebook)
                notebookMap[notebook.id] = notebook
            }
            
            // Load current selection
            _currentNotebookId = json.optString("currentNotebookId", "").takeIf { it.isNotEmpty() }
            _currentPageId = json.optString("currentPageId", "").takeIf { it.isNotEmpty() }
            
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load notebooks index: ${t.message}", t)
            _notebooks.clear()
            notebookMap.clear()
            _currentNotebookId = null
            _currentPageId = null
        }
    }
    
    /**
     * Load notebook metadata files.
     */
    private fun loadNotebookMetadata() {
        for (notebook in _notebooks) {
            val metaFile = File(File(notebooksDir, notebook.id), "meta.json")
            if (!metaFile.exists()) continue
            
            try {
                JSONObject(metaFile.readText())
                // Notebook metadata is already loaded from index, but we can update here
                // For now, just ensure the notebook exists
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load metadata for notebook ${notebook.id}: ${t.message}")
            }
        }
    }
    
    /**
     * Load page metadata files.
     */
    private fun loadPageMetadata() {
        pageMap.clear()
        
        for (notebook in _notebooks) {
            val pagesDir = File(File(notebooksDir, notebook.id), "pages")
            if (!pagesDir.exists()) continue
            
            val metaFile = File(pagesDir, "meta.json")
            if (!metaFile.exists()) continue
            
            try {
                val json = JSONObject(metaFile.readText())
                val pagesArray = json.optJSONArray("pages") ?: JSONArray()
                
                for (i in 0 until pagesArray.length()) {
                    val obj = pagesArray.getJSONObject(i)
                    val page = Page(
                        id = obj.getString("id"),
                        notebookId = notebook.id,
                        createdAt = obj.getLong("createdAt"),
                        updatedAt = obj.getLong("updatedAt"),
                        backgroundType = obj.optInt("backgroundType", 0),
                        width = obj.optInt("width", 0),
                        height = obj.optInt("height", 0),
                        documentHeightFactor = obj.optInt("documentHeightFactor", 2),
                        title = obj.optString("title", "").takeIf { it.isNotEmpty() },
                        isTemplate = obj.optBoolean("isTemplate", false),
                        sortOrder = obj.optInt("sortOrder", 0)
                    )
                    
                    pageMap[page.id] = page
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load page metadata for notebook ${notebook.id}: ${t.message}")
            }
        }
        
        // If no pages were loaded, try to discover pages from PNG files
        for (notebook in _notebooks) {
            val pagesDir = File(File(notebooksDir, notebook.id), "pages")
            if (!pagesDir.exists()) continue
            
            val pngFiles = pagesDir.listFiles()?.filter { 
                it.isFile && it.name.endsWith(".png") && !it.name.endsWith(".tmp")
            } ?: continue
            
            for (file in pngFiles) {
                val pageId = file.name.removeSuffix(".png")
                if (pageMap.containsKey(pageId)) continue
                
                // Create a default page entry
                val page = Page(
                    id = pageId,
                    notebookId = notebook.id
                )
                pageMap[pageId] = page
                notebook.addPageId(pageId)
            }
        }
    }
    
    /**
     * Save the notebooks index.
     */
    private fun saveNotebooksIndex() {
        try {
            val array = JSONArray()
            for (notebook in _notebooks) {
                val obj = JSONObject().apply {
                    put("id", notebook.id)
                    put("name", notebook.name)
                    put("createdAt", notebook.createdAt)
                    put("updatedAt", notebook.updatedAt)
                    put("pageIds", JSONArray(notebook.pageIds))
                    put("coverColor", notebook.coverColor ?: -1)
                    put("isLocked", notebook.isLocked)
                    put("sortOrder", notebook.sortOrder)
                    put("isFavorite", notebook.isFavorite)
                }
                array.put(obj)
            }
            
            val json = JSONObject().apply {
                put("notebooks", array)
                put("currentNotebookId", _currentNotebookId ?: "")
                put("currentPageId", _currentPageId ?: "")
            }
            
            notebooksIndexFile.writeText(json.toString())
            Log.i(TAG, "Saved notebooks index")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save notebooks index: ${t.message}", t)
        }
    }
    
    /**
     * Save notebook metadata.
     */
    private fun saveNotebookMetadata(notebook: Notebook) {
        try {
            val dir = File(notebooksDir, notebook.id).apply { mkdirs() }
            val metaFile = File(dir, "meta.json")
            
            val json = JSONObject().apply {
                put("id", notebook.id)
                put("name", notebook.name)
                put("createdAt", notebook.createdAt)
                put("updatedAt", notebook.updatedAt)
            }
            
            metaFile.writeText(json.toString())
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save notebook metadata: ${t.message}", t)
        }
    }
    
    /**
     * Save page metadata for a notebook (public method).
     */
    fun savePageMetadata(page: Page) {
        savePageMetadataInternal(page)
    }
    
    /**
     * Internal method to save page metadata.
     */
    private fun savePageMetadataInternal(page: Page) {
        val notebook = notebookMap[page.notebookId] ?: return
        val pagesDir = File(File(notebooksDir, notebook.id), "pages").apply { mkdirs() }
        val metaFile = File(pagesDir, "meta.json")
        
        // Load existing metadata
        val pages = mutableListOf<Page>()
        if (metaFile.exists()) {
            try {
                val json = JSONObject(metaFile.readText())
                val array = json.optJSONArray("pages") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    pages.add(Page(
                        id = obj.getString("id"),
                        notebookId = notebook.id,
                        createdAt = obj.getLong("createdAt"),
                        updatedAt = obj.getLong("updatedAt"),
                        backgroundType = obj.optInt("backgroundType", 0),
                        width = obj.optInt("width", 0),
                        height = obj.optInt("height", 0),
                        documentHeightFactor = obj.optInt("documentHeightFactor", 2),
                        title = obj.optString("title", "").takeIf { it.isNotEmpty() },
                        isTemplate = obj.optBoolean("isTemplate", false),
                        sortOrder = obj.optInt("sortOrder", 0)
                    ))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load existing page metadata: ${t.message}")
            }
        }
        
        // Update or add the page
        val existingIndex = pages.indexOfFirst { it.id == page.id }
        if (existingIndex >= 0) {
            pages[existingIndex] = page
        } else {
            pages.add(page)
        }
        
        // Save updated metadata
        try {
            val array = JSONArray()
            for (p in pages) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("notebookId", p.notebookId)
                    put("createdAt", p.createdAt)
                    put("updatedAt", p.updatedAt)
                    put("backgroundType", p.backgroundType)
                    put("width", p.width)
                    put("height", p.height)
                    put("documentHeightFactor", p.documentHeightFactor)
                    put("title", p.title ?: "")
                    put("isTemplate", p.isTemplate)
                    put("sortOrder", p.sortOrder)
                }
                array.put(obj)
            }
            
            val json = JSONObject().apply {
                put("pages", array)
            }
            
            metaFile.writeText(json.toString())
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save page metadata: ${t.message}", t)
        }
    }
    
    // ============================================
    // Notebook Operations
    // ============================================
    
    /**
     * Create a new notebook.
     */
    fun createNotebook(name: String = Notebook.DEFAULT_NAME, coverColor: Int? = null): Notebook {
        ensureInitialized()
        
        val notebook = Notebook(
            name = name,
            coverColor = coverColor,
            sortOrder = _notebooks.size
        )
        
        _notebooks.add(notebook)
        notebookMap[notebook.id] = notebook
        
        saveNotebookMetadata(notebook)
        saveNotebooksIndex()
        
        Log.i(TAG, "Created notebook: ${notebook.id} - $name")
        return notebook
    }
    
    /**
     * Delete a notebook and all its pages.
     */
    fun deleteNotebook(notebookId: String): Boolean {
        ensureInitialized()
        
        val notebook = notebookMap[notebookId] ?: return false
        
        // Delete all pages
        for (pageId in notebook.pageIds) {
            deletePageInternal(pageId, deleteFromNotebook = false)
        }
        
        // Delete notebook directory
        val notebookDir = File(notebooksDir, notebookId)
        notebookDir.deleteRecursively()
        
        // Remove from lists
        _notebooks.removeIf { it.id == notebookId }
        notebookMap.remove(notebookId)
        
        // Update current selection if needed
        if (_currentNotebookId == notebookId) {
            _currentNotebookId = _notebooks.firstOrNull()?.id
            _currentPageId = null
        }
        
        saveNotebooksIndex()
        
        Log.i(TAG, "Deleted notebook: $notebookId")
        return true
    }
    
    /**
     * Rename a notebook.
     */
    fun renameNotebook(notebookId: String, newName: String): Boolean {
        ensureInitialized()
        
        val notebook = notebookMap[notebookId] ?: return false
        notebook.name = newName
        notebook.updatedAt = System.currentTimeMillis()
        
        saveNotebookMetadata(notebook)
        saveNotebooksIndex()
        
        Log.i(TAG, "Renamed notebook: $notebookId to $newName")
        return true
    }
    
    /**
     * Set the current notebook.
     */
    fun setCurrentNotebook(notebookId: String): Boolean {
        ensureInitialized()
        
        if (!notebookMap.containsKey(notebookId)) return false
        
        _currentNotebookId = notebookId
        val notebook = notebookMap[notebookId]!!
        
        // Set current page to first page in notebook
        if (notebook.pageIds.isNotEmpty()) {
            _currentPageId = notebook.pageIds[0]
        } else {
            _currentPageId = null
        }
        
        saveNotebooksIndex()
        Log.i(TAG, "Set current notebook: $notebookId")
        return true
    }
    
    /**
     * Set the current notebook and page.
     */
    fun setCurrentNotebookAndPage(notebookId: String, pageId: String): Boolean {
        ensureInitialized()
        
        if (!notebookMap.containsKey(notebookId)) return false
        if (!pageMap.containsKey(pageId)) return false
        
        val page = pageMap[pageId]!!
        if (page.notebookId != notebookId) return false
        
        _currentNotebookId = notebookId
        _currentPageId = pageId
        
        saveNotebooksIndex()
        Log.i(TAG, "Set current notebook: $notebookId, page: $pageId")
        return true
    }
    
    /**
     * Reorder notebooks.
     */
    fun reorderNotebooks(newOrder: List<String>): Boolean {
        ensureInitialized()
        
        val validIds = notebookMap.keys
        val filteredOrder = newOrder.filter { validIds.contains(it) }
        
        if (filteredOrder.size != notebookMap.size) return false
        
        for ((index, notebookId) in filteredOrder.withIndex()) {
            notebookMap[notebookId]?.sortOrder = index
        }
        
        _notebooks.sortBy { notebookMap[it.id]?.sortOrder ?: 0 }
        
        saveNotebooksIndex()
        Log.i(TAG, "Reordered notebooks")
        return true
    }
    
    // ============================================
    // Page Operations
    // ============================================
    
    /**
     * Create a new page in a notebook.
     */
    fun createPage(
        notebookId: String,
        backgroundType: Int = Page.BACKGROUND_NONE,
        width: Int = 0,
        height: Int = 0,
        inheritBackgroundType: Int? = null
    ): Page? {
        ensureInitialized()
        
        val notebook = notebookMap[notebookId] ?: return null
        
        // Use inheritBackgroundType if provided, otherwise use backgroundType
        val actualBackgroundType = inheritBackgroundType ?: backgroundType
        
        val page = Page.fromLegacy(
            notebookId = notebook.id,
            legacyBackgroundType = actualBackgroundType,
            width = width,
            height = height
        )
        
        val pageId = page.id
        notebook.addPageId(pageId)
        notebook.updatedAt = System.currentTimeMillis()
        
        pageMap[pageId] = page
        
        // Create page directory and save metadata
        File(File(notebooksDir, notebook.id), "pages").apply { mkdirs() }
        savePageMetadataInternal(page)
        
        // Set as current page if this is the current notebook
        if (_currentNotebookId == notebook.id) {
            _currentPageId = pageId
        }
        
        saveNotebookMetadata(notebook)
        saveNotebooksIndex()
        
        Log.i(TAG, "Created page: $pageId in notebook: $notebookId")
        return page
    }
    
    /**
     * Delete a page from its notebook.
     */
    fun deletePage(pageId: String): Boolean {
        ensureInitialized()
        
        val page = pageMap[pageId] ?: return false
        
        // Remove from notebook
        val notebook = notebookMap[page.notebookId] ?: return false
        notebook.removePageId(pageId)
        notebook.updatedAt = System.currentTimeMillis()
        
        // Delete the page file
        deletePageInternal(pageId, deleteFromNotebook = false)
        
        // Update current page if needed
        if (_currentPageId == pageId) {
            _currentPageId = notebook.pageIds.firstOrNull()
        }
        
        saveNotebookMetadata(notebook)
        saveNotebooksIndex()
        
        Log.i(TAG, "Deleted page: $pageId from notebook: ${page.notebookId}")
        return true
    }
    
    /**
     * Internal method to delete a page file.
     */
    private fun deletePageInternal(pageId: String, deleteFromNotebook: Boolean) {
        val page = pageMap[pageId] ?: return
        
        // Remove from page map
        pageMap.remove(pageId)
        
        // Delete the PNG file
        val notebook = notebookMap[page.notebookId] ?: return
        val pagesDir = File(File(notebooksDir, notebook.id), "pages")
        val pngFile = File(pagesDir, "$pageId.png")
        if (pngFile.exists()) {
            pngFile.delete()
        }
        
        // Delete the temp file if it exists
        val tmpFile = File(pagesDir, "$pageId.png.tmp")
        if (tmpFile.exists()) {
            tmpFile.delete()
        }
        
        // Update notebook if needed
        if (deleteFromNotebook) {
            notebook.removePageId(pageId)
        }
    }
    
    /**
     * Set the current page.
     */
    fun setCurrentPage(pageId: String): Boolean {
        ensureInitialized()
        
        val page = pageMap[pageId] ?: return false
        
        _currentPageId = pageId
        _currentNotebookId = page.notebookId
        
        saveNotebooksIndex()
        Log.i(TAG, "Set current page: $pageId")
        return true
    }
    
    /**
     * Move a page to a different notebook.
     */
    fun movePage(pageId: String, newNotebookId: String): Boolean {
        ensureInitialized()
        
        val page = pageMap[pageId] ?: return false
        if (page.notebookId == newNotebookId) return false
        
        val oldNotebook = notebookMap[page.notebookId] ?: return false
        val newNotebook = notebookMap[newNotebookId] ?: return false
        
        // Remove from old notebook
        oldNotebook.removePageId(pageId)
        oldNotebook.updatedAt = System.currentTimeMillis()
        
        // Add to new notebook
        newNotebook.addPageId(pageId)
        newNotebook.updatedAt = System.currentTimeMillis()
        
        // Update page
        pageMap[pageId] = page.copy(notebookId = newNotebookId)
        
        // Update current selection if needed
        if (_currentPageId == pageId) {
            _currentNotebookId = newNotebookId
        }
        
        saveNotebookMetadata(oldNotebook)
        saveNotebookMetadata(newNotebook)
        saveNotebooksIndex()
        
        Log.i(TAG, "Moved page: $pageId from ${page.notebookId} to $newNotebookId")
        return true
    }
    
    /**
     * Reorder pages within a notebook.
     */
    fun reorderPages(notebookId: String, newOrder: List<String>): Boolean {
        ensureInitialized()
        
        val notebook = notebookMap[notebookId] ?: return false
        
        // Validate that all page IDs belong to this notebook
        val validPageIds = pageMap.filter { it.value.notebookId == notebookId }.keys
        val filteredOrder = newOrder.filter { validPageIds.contains(it) }
        
        if (filteredOrder.size != notebook.pageIds.size) return false
        
        notebook.reorderPages(filteredOrder)
        notebook.updatedAt = System.currentTimeMillis()
        
        saveNotebookMetadata(notebook)
        saveNotebooksIndex()
        
        Log.i(TAG, "Reordered pages in notebook: $notebookId")
        return true
    }
    
    // ============================================
    // Legacy Compatibility
    // ============================================
    
    /**
     * Check if legacy data exists and needs migration.
     */
    fun hasLegacyData(): Boolean {
        return legacyDir.exists() && legacyDir.listFiles()?.isNotEmpty() == true
    }
    
    /**
     * Get migration state.
     */
    fun getMigrationState(): MigrationState {
        if (!hasLegacyData()) {
            return MigrationState.NOT_NEEDED
        }
        
        if (notebooksIndexFile.exists()) {
            return MigrationState.COMPLETED
        }
        
        return MigrationState.PENDING
    }
    
    // ============================================
    // Content Operations
    // ============================================
    
    /**
     * Load page content (ink strokes) as a bitmap.
     * Returns a transparent bitmap with only the ink strokes.
     */
    fun loadPageContent(pageId: String, width: Int, height: Int): Bitmap {
        val page = pageMap[pageId] ?: return createBlankBitmap(width, height)
        
        val notebook = notebookMap[page.notebookId] ?: return createBlankBitmap(width, height)
        val pagesDir = File(File(notebooksDir, notebook.id), "pages")
        val pngFile = File(pagesDir, "$pageId.png")
        
        // Create transparent bitmap
        val bitmap = createBlankBitmap(width, height)
        
        if (pngFile.exists()) {
            try {
                val decoded = BitmapFactory.decodeFile(pngFile.absolutePath)
                if (decoded != null) {
                    // Draw decoded content onto transparent bitmap
                    Canvas(bitmap).drawBitmap(decoded, 0f, 0f, null)
                    decoded.recycle()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load page content: ${t.message}")
            }
        }
        
        return bitmap
    }
    
    /**
     * Save page content (ink strokes) as a bitmap.
     * The bitmap should be transparent with only ink strokes.
     */
    fun savePageContent(pageId: String, bitmap: Bitmap) {
        val page = pageMap[pageId] ?: return
        val notebook = notebookMap[page.notebookId] ?: return
        
        val pagesDir = File(File(notebooksDir, notebook.id), "pages").apply { mkdirs() }
        val tmpFile = File(pagesDir, "$pageId.png.tmp")
        val finalFile = File(pagesDir, "$pageId.png")
        
        try {
            FileOutputStream(tmpFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            tmpFile.renameTo(finalFile)
            
            // Update page updated timestamp
            val updatedPage = page.copy(updatedAt = System.currentTimeMillis())
            pageMap[pageId] = updatedPage
            savePageMetadata(updatedPage)
            
            Log.i(TAG, "Saved page content: $pageId")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save page content: ${t.message}", t)
            tmpFile.delete()
        }
    }
    
    /**
     * Create a blank transparent bitmap.
     */
    private fun createBlankBitmap(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        }
    }
    
    // ============================================
    // Utility Methods
    // ============================================
    
    /**
     * Ensure the store is initialized.
     */
    private fun ensureInitialized() {
        if (!isInitialized) {
            load()
        }
    }
    
    /**
     * Get all notebooks sorted by sortOrder and name.
     */
    fun getAllNotebooksSorted(): List<Notebook> {
        ensureInitialized()
        return _notebooks.sortedWith(compareBy(
            { it.sortOrder },
            { it.name },
            { it.createdAt }
        ))
    }
    
    /**
     * Get all pages across all notebooks.
     */
    fun getAllPages(): List<Page> {
        ensureInitialized()
        return pageMap.values.toList()
    }
    
    /**
     * Get total page count across all notebooks.
     */
    fun getTotalPageCount(): Int {
        ensureInitialized()
        return pageMap.size
    }
    
    /**
     * Get total notebook count.
     */
    fun getNotebookCount(): Int {
        ensureInitialized()
        return _notebooks.size
    }
    
    /**
     * Clear all data (for testing or reset).
     */
    fun clearAll() {
        _notebooks.clear()
        notebookMap.clear()
        pageMap.clear()
        _currentNotebookId = null
        _currentPageId = null
        
        notebooksDir.deleteRecursively()
        notebooksDir.mkdirs()
        
        isInitialized = false
        
        Log.i(TAG, "Cleared all notebooks and pages")
    }
    
    companion object {
        private const val TAG = "NotebookStore"
        
        /** Default notebook ID for migrated data */
        private const val DEFAULT_NOTEBOOK_ID = "legacy-notebook"
        
        /** Singleton instance */
        @Volatile
        private var instance: NotebookStore? = null
        
        /**
         * Get or create the singleton instance.
         */
        fun getInstance(context: Context): NotebookStore {
            return instance ?: synchronized(this) {
                instance ?: NotebookStore(context.applicationContext).also { instance = it }
            }
        }
        
        /**
         * Reset the singleton instance (for testing).
         */
        fun resetInstance() {
            instance = null
        }
    }
}
