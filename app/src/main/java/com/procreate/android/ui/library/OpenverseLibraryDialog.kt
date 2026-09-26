package com.procreate.android.ui.library

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.procreate.android.R
import com.procreate.android.ui.common.PanelMotion
import com.procreate.android.ui.common.PanelUi
import kotlinx.coroutines.launch

/** Search-and-preview UI for openly licensed images from Openverse. */
class OpenverseLibraryDialog : DialogFragment() {
    private var onImageSelected: ((OpenverseImage) -> Unit)? = null
    private lateinit var searchInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var recycler: RecyclerView
    private lateinit var moreButton: View
    private val adapter = ResultAdapter { showImageDetails(it) }
    private var activeQuery = "architecture design"
    private var currentPage = 0
    private var loading = false
    private val compactLandscape: Boolean
        get() = resources.configuration.screenHeightDp < 500

    fun onSelected(callback: (OpenverseImage) -> Unit) = apply { onImageSelected = callback }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_panel_rounded)
            setPadding(dp(12), dp(if (compactLandscape) 6 else 10), dp(12), dp(if (compactLandscape) 6 else 10))
        }
        root.addView(PanelUi.panelTitle(context, getString(R.string.openverse_title)))
        root.addView(TextView(context).apply {
            setText(R.string.openverse_subtitle)
            textSize = if (compactLandscape) 11.5f else 12.5f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dp(18), 0, dp(18), dp(if (compactLandscape) 5 else 10))
        })

        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(6))
        }
        searchInput = EditText(context).apply {
            hint = getString(R.string.openverse_search_hint)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_hint))
            background = ContextCompat.getDrawable(context, R.drawable.bg_field_outline)
            setPadding(dp(14), 0, dp(14), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    beginSearch(searchInput.text.toString())
                    true
                } else false
            }
        }
        val searchHeight = if (compactLandscape) 44 else 50
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(searchHeight), 1f).apply {
            marginEnd = dp(8)
        })
        searchRow.addView(
            PanelUi.filledButton(context, getString(R.string.openverse_search)) {
                beginSearch(searchInput.text.toString())
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(searchHeight))
        )
        root.addView(searchRow)

        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), 0, dp(12), dp(8))
                quickQueries().forEach { (label, query) ->
                    addView(queryChip(label, query), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(if (compactLandscape) 34 else 38)
                    ).apply { marginEnd = dp(7) })
                }
            })
        })

        progress = ProgressBar(context).apply { visibility = View.GONE }
        status = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dp(20), dp(16), dp(20), dp(16))
            visibility = View.GONE
        }
        recycler = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, resultColumnCount())
            adapter = this@OpenverseLibraryDialog.adapter
            setPadding(dp(8), dp(4), dp(8), dp(8))
            clipToPadding = false
        }
        root.addView(FrameLayout(context).apply {
            addView(recycler, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(status, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
            addView(progress, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(context).apply {
            setText(R.string.openverse_license_notice)
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            setPadding(dp(18), dp(5), dp(18), dp(5))
            visibility = if (compactLandscape) View.GONE else View.VISIBLE
        })
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        moreButton = PanelUi.filledButton(context, getString(R.string.openverse_more)) { loadNextPage() }
            .apply { visibility = View.GONE }
        val footerHeight = if (compactLandscape) 42 else 46
        footer.addView(moreButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(footerHeight)).apply {
            marginEnd = dp(8)
        })
        footer.addView(PanelUi.filledButton(context, getString(R.string.library_close)) { dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(footerHeight)))
        root.addView(footer)

        return Dialog(context, android.R.style.Theme_Material_Dialog_NoActionBar).apply {
            setContentView(root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            minOf(dp(980), resources.displayMetrics.widthPixels - dp(28)),
            minOf(dp(700), resources.displayMetrics.heightPixels - dp(28))
        )
        if (currentPage == 0) beginSearch(activeQuery)
    }

    private fun beginSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty() || loading) return
        activeQuery = query
        currentPage = 0
        adapter.replace(emptyList())
        loadNextPage()
    }

    private fun loadNextPage() {
        if (loading) return
        loading = true
        progress.visibility = View.VISIBLE
        status.visibility = View.GONE
        moreButton.visibility = View.GONE
        val requestedPage = currentPage + 1
        lifecycleScope.launch {
            val result = runCatching { OpenverseClient.search(activeQuery, requestedPage) }
            if (!isAdded) return@launch
            loading = false
            progress.visibility = View.GONE
            result.onSuccess { images ->
                if (images.isEmpty() && currentPage == 0) {
                    status.setText(R.string.openverse_no_results)
                    status.visibility = View.VISIBLE
                } else {
                    currentPage = requestedPage
                    adapter.append(images)
                    recycler.visibility = View.VISIBLE
                    moreButton.visibility = if (images.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }.onFailure {
                status.setText(R.string.openverse_error)
                status.visibility = View.VISIBLE
            }
        }
    }

    private fun queryChip(label: String, query: String): View = TextView(requireContext()).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 12.5f
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        background = ContextCompat.getDrawable(context, R.drawable.bg_list_item_selected)
        setPadding(dp(15), 0, dp(15), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            searchInput.setText(label)
            beginSearch(query)
        }
        PanelMotion.press(this)
    }

    private fun quickQueries() = listOf(
        getString(R.string.openverse_category_architecture) to "architecture design",
        getString(R.string.openverse_category_people) to "people silhouette",
        getString(R.string.openverse_category_plants) to "tree plant illustration",
        getString(R.string.openverse_category_furniture) to "furniture illustration",
        getString(R.string.openverse_category_textures) to "seamless texture background"
    )

    private fun showImageDetails(image: OpenverseImage) {
        val creator = image.creator.ifBlank { getString(R.string.openverse_unknown_creator) }
        val message = getString(
            R.string.openverse_image_details,
            creator,
            image.licenseLabel
        )
        AlertDialog.Builder(requireContext())
            .setTitle(image.title.ifBlank { getString(R.string.openverse_untitled) })
            .setMessage(message)
            .setPositiveButton(R.string.openverse_import) { _, _ ->
                dismiss()
                onImageSelected?.invoke(image)
            }
            .setNeutralButton(R.string.openverse_source) { _, _ ->
                openUrl(image.sourceUrl.ifBlank { image.licenseUrl })
            }
            .setNegativeButton(R.string.openverse_cancel, null)
            .show()
    }

    private fun openUrl(url: String) {
        if (!url.startsWith("https://", ignoreCase = true)) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    private fun resultColumnCount(): Int = when {
        resources.displayMetrics.widthPixels >= dp(1100) -> 5
        resources.displayMetrics.widthPixels >= dp(760) -> 4
        else -> 3
    }

    private fun dp(value: Int) = PanelUi.dp(requireContext(), value)

    private inner class ResultAdapter(
        private val onClick: (OpenverseImage) -> Unit
    ) : RecyclerView.Adapter<ResultAdapter.Holder>() {
        private val items = mutableListOf<OpenverseImage>()

        fun replace(newItems: List<OpenverseImage>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun append(newItems: List<OpenverseImage>) {
            val unique = newItems.filter { candidate -> items.none { it.id == candidate.id } }
            val start = items.size
            items.addAll(unique)
            notifyItemRangeInserted(start, unique.size)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_list_item_ripple)
                setPadding(dp(7), dp(7), dp(7), dp(9))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                isClickable = true
                isFocusable = true
                PanelMotion.press(this)
            }
            val preview = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ContextCompat.getColor(context, R.color.surface_3))
            }
            card.addView(preview, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (compactLandscape) 82 else 116)
            ))
            val title = TextView(context).apply {
                textSize = 12.5f
                maxLines = 2
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(dp(2), dp(7), dp(2), 0)
            }
            card.addView(title)
            val credit = TextView(context).apply {
                textSize = 10.5f
                maxLines = 2
                setTextColor(ContextCompat.getColor(context, R.color.text_hint))
                setPadding(dp(2), dp(3), dp(2), 0)
            }
            card.addView(credit)
            return Holder(card, preview, title, credit)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title.ifBlank { getString(R.string.openverse_untitled) }
            val creator = item.creator.ifBlank { getString(R.string.openverse_unknown_creator) }
            holder.credit.text = "$creator · ${item.licenseLabel}"
            Glide.with(holder.preview)
                .load(item.thumbnailUrl)
                .centerCrop()
                .placeholder(R.drawable.ic_image)
                .error(R.drawable.ic_image)
                .into(holder.preview)
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun onViewRecycled(holder: Holder) {
            Glide.with(holder.preview).clear(holder.preview)
            super.onViewRecycled(holder)
        }

        override fun getItemCount() = items.size

        inner class Holder(
            itemView: View,
            val preview: ImageView,
            val title: TextView,
            val credit: TextView
        ) : RecyclerView.ViewHolder(itemView)
    }
}
