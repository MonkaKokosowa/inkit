package com.merrythieves.inkit.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.merrythieves.inkit.R
import com.merrythieves.inkit.model.Notebook
import com.merrythieves.inkit.store.NotebookStore
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Adapter for displaying notebooks in a list.
 */
class NotebookAdapter(
    private val context: android.content.Context,
    private var notebooks: List<Notebook>,
    private val onNotebookClick: (Notebook) -> Unit,
    private val onNotebookMoreClick: (Notebook, View) -> Unit
) : RecyclerView.Adapter<NotebookAdapter.NotebookViewHolder>() {
    
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    private val notebookStore: NotebookStore = NotebookStore.getInstance(context)
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotebookViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notebook_card, parent, false)
        return NotebookViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: NotebookViewHolder, position: Int) {
        val notebook = notebooks[position]
        
        holder.txtNotebookName.text = notebook.name
        holder.txtPageCount.text = "${notebook.pageIds.size} pages"
        
        // Set color bar
        holder.colorBar.setBackgroundColor(notebook.getEffectiveCoverColor())
        
        // Set last updated date
        val updatedDate = dateFormat.format(notebook.updatedAt)
        holder.txtLastUpdated.text = "Updated $updatedDate"
        
        // Set click listeners
        holder.itemView.setOnClickListener {
            onNotebookClick(notebook)
        }
        
        holder.btnMore.setOnClickListener { view ->
            onNotebookMoreClick(notebook, view)
        }
    }
    
    override fun getItemCount(): Int = notebooks.size
    
    /**
     * Update the notebooks list.
     */
    fun updateNotebooks(newNotebooks: List<Notebook>) {
        notebooks = newNotebooks
        notifyDataSetChanged()
    }
    
    inner class NotebookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtNotebookName: TextView = itemView.findViewById(R.id.txtNotebookName)
        val txtPageCount: TextView = itemView.findViewById(R.id.txtPageCount)
        val txtLastUpdated: TextView = itemView.findViewById(R.id.txtLastUpdated)
        val colorBar: View = itemView.findViewById(R.id.colorBar)
        val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)
    }
}
