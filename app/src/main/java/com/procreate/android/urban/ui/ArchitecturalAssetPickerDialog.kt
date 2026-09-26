package com.procreate.android.urban.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.procreate.android.R
import com.procreate.android.urban.assets.ArchitecturalAssetCategory
import com.procreate.android.urban.assets.ArchitecturalAssetMetadata
import com.procreate.android.urban.assets.ArchitecturalAssetPreviewView
import com.procreate.android.urban.assets.BuiltInArchitecturalAssets
import java.text.DecimalFormat

/**
 * Searchable browser for the original architectural resources bundled with Urban Design.
 *
 * Use [show] for the direct callback API. The selected stable asset id is also published through
 * [RESULT_KEY], so a host may register a FragmentResultListener when it wants the selection to
 * survive Activity/Fragment recreation.
 */
class ArchitecturalAssetPickerDialog : BottomSheetDialogFragment() {

    private val catalog = BuiltInArchitecturalAssets.catalog
    private var selectionCallback: ((ArchitecturalAssetMetadata) -> Unit)? = null
    private var activeCategory: ArchitecturalAssetCategory? = null
    private var query: String = ""
    private var selectedAssetId: String? = null

    private lateinit var searchEditText: TextInputEditText
    private lateinit var allCategoriesChip: Chip
    private lateinit var resultCountView: TextView
    private lateinit var resultsView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var emptyTitleView: TextView
    private lateinit var adapter: ArchitecturalAssetAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedAssetId = savedInstanceState?.getString(STATE_SELECTED_ASSET_ID)
            ?: arguments?.getString(ARG_SELECTED_ASSET_ID)
        query = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        activeCategory = savedInstanceState?.getString(STATE_CATEGORY)
            ?.let { stored -> ArchitecturalAssetCategory.entries.firstOrNull { it.name == stored } }
    }

    override fun onStart() {
        super.onStart()
        val sheetDialog = dialog as? BottomSheetDialog ?: return
        val sheet = sheetDialog.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isFitToContents = true
            peekHeight = dp(requireContext(), 620)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface_1))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(
                    dp(context, 760),
                    (resources.displayMetrics.heightPixels * 0.90f).toInt()
                )
            )
        }

        root.addView(buildGrabber(context))
        root.addView(buildHeader(context))
        root.addView(buildSearchField(context))
        root.addView(buildCategoryFilters(context))

        resultCountView = TextView(context).apply {
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(resultCountView)

        val resultFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        adapter = ArchitecturalAssetAdapter(
            initialSelectedAssetId = selectedAssetId,
            onAssetClick = ::deliverSelection
        )
        resultsView = RecyclerView(context).apply {
            val availableWidth = resources.displayMetrics.widthPixels - dp(context, 32)
            val spanCount = (availableWidth / dp(context, 124)).coerceIn(2, 4)
            layoutManager = GridLayoutManager(context, spanCount)
            adapter = this@ArchitecturalAssetPickerDialog.adapter
            clipToPadding = false
            setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 20))
            isNestedScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            contentDescription = "نتائج مكتبة الموارد المعمارية"
        }
        emptyView = buildEmptyState(context)
        resultFrame.addView(
            resultsView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        resultFrame.addView(
            emptyView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(resultFrame)

        searchEditText.setText(query)
        searchEditText.setSelection(searchEditText.text?.length ?: 0)
        refreshResults()
        return root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_QUERY, query)
        outState.putString(STATE_CATEGORY, activeCategory?.name)
        outState.putString(STATE_SELECTED_ASSET_ID, selectedAssetId)
        super.onSaveInstanceState(outState)
    }

    /** Assigns or replaces the direct listener before showing this dialog. */
    fun setOnAssetSelectedListener(
        listener: (ArchitecturalAssetMetadata) -> Unit
    ): ArchitecturalAssetPickerDialog = apply {
        selectionCallback = listener
    }

    private fun buildGrabber(context: Context): View = View(context).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(context, 2).toFloat()
            setColor(ContextCompat.getColor(context, R.color.surface_4))
        }
        layoutParams = LinearLayout.LayoutParams(dp(context, 42), dp(context, 4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(context, 10)
            bottomMargin = dp(context, 8)
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun buildHeader(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 20), 0, dp(context, 12), dp(context, 8))
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(context).apply {
            text = "مكتبة الموارد المعمارية"
            textSize = 19f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
        })
        textColumn.addView(TextView(context).apply {
            text = "اختر عنصرًا لإضافته إلى المخطط"
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(context, 3), 0, 0)
        })
        row.addView(textColumn)
        row.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "إغلاق"
            textSize = 13f
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            minimumHeight = dp(context, 48)
            minHeight = dp(context, 48)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.outline_subtle))
            rippleColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ripple_light))
            contentDescription = "إغلاق مكتبة الموارد"
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48)))
        return row
    }

    private fun buildSearchField(context: Context): View {
        searchEditText = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            maxLines = 1
            minHeight = dp(context, 56)
            textSize = 15f
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_hint))
            doAfterTextChanged {
                query = it?.toString().orEmpty()
                if (::adapter.isInitialized) refreshResults()
            }
        }
        return TextInputLayout(
            context,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "ابحث عن شجرة، شخص أو سيارة"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(
                dp(context, 14).toFloat(),
                dp(context, 14).toFloat(),
                dp(context, 14).toFloat(),
                dp(context, 14).toFloat()
            )
            boxStrokeColor = ContextCompat.getColor(context, R.color.procreate_accent_light)
            defaultHintTextColor = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.text_hint)
            )
            setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT)
            setEndIconTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.icon_color)))
            addView(searchEditText)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(context, 16)
                marginEnd = dp(context, 16)
            }
        }
    }

    private fun buildCategoryFilters(context: Context): View {
        val chipGroup = ChipGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            chipSpacingHorizontal = dp(context, 8)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 2))
        }
        val filters = listOf(
            "الكل" to null,
            "الأشجار" to ArchitecturalAssetCategory.TREES,
            "الأشخاص" to ArchitecturalAssetCategory.PEOPLE,
            "المركبات" to ArchitecturalAssetCategory.VEHICLES
        )
        filters.forEach { (label, category) ->
            val chip = categoryChip(context, label, category == activeCategory).apply {
                setOnClickListener {
                    activeCategory = category
                    refreshResults()
                }
            }
            if (category == null) allCategoriesChip = chip
            chipGroup.addView(chip)
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(
                chipGroup,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun categoryChip(context: Context, label: String, checked: Boolean): Chip {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf()
        )
        return Chip(context).apply {
            id = View.generateViewId()
            text = label
            textSize = 13.5f
            isCheckable = true
            isChecked = checked
            isCheckedIconVisible = false
            chipMinHeight = dp(context, 48).toFloat()
            minHeight = dp(context, 48)
            ensureAccessibleTouchTarget(dp(context, 48))
            chipCornerRadius = dp(context, 14).toFloat()
            chipStrokeWidth = dp(context, 1).toFloat()
            chipBackgroundColor = ColorStateList(
                states,
                intArrayOf(
                    ContextCompat.getColor(context, R.color.procreate_accent),
                    ContextCompat.getColor(context, R.color.surface_4),
                    ContextCompat.getColor(context, R.color.surface_3)
                )
            )
            chipStrokeColor = ColorStateList(
                states,
                intArrayOf(
                    ContextCompat.getColor(context, R.color.procreate_accent_light),
                    ContextCompat.getColor(context, R.color.outline_subtle),
                    ContextCompat.getColor(context, R.color.outline_subtle)
                )
            )
            setTextColor(
                ColorStateList(
                    states,
                    intArrayOf(
                        Color.WHITE,
                        ContextCompat.getColor(context, R.color.text_primary),
                        ContextCompat.getColor(context, R.color.text_secondary)
                    )
                )
            )
            rippleColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ripple_light))
        }
    }

    private fun buildEmptyState(context: Context): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(context, 32), dp(context, 24), dp(context, 32), dp(context, 24))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        emptyTitleView = TextView(context).apply {
            text = "لا توجد موارد مطابقة"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
        }
        column.addView(emptyTitleView)
        column.addView(TextView(context).apply {
            text = "جرّب كلمة أخرى أو اختر فئة مختلفة."
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(context, 6), 0, dp(context, 14))
        })
        column.addView(MaterialButton(context).apply {
            text = "مسح عوامل البحث"
            isAllCaps = false
            minimumHeight = dp(context, 48)
            minHeight = dp(context, 48)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.procreate_accent)
            )
            rippleColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ripple_light))
            setOnClickListener {
                activeCategory = null
                allCategoriesChip.isChecked = true
                searchEditText.text?.clear()
                refreshResults()
                searchEditText.requestFocus()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48)))
        return column
    }

    private fun refreshResults() {
        if (!::adapter.isInitialized) return
        val matches = catalog.search(query, activeCategory)
        adapter.submitAssets(matches)
        val total = catalog.assets.size
        resultCountView.text = when {
            matches.size == total && query.isBlank() && activeCategory == null ->
                "$total موارد أصلية جاهزة للاستخدام"
            else -> "عرض ${matches.size} من $total موارد"
        }
        val isEmpty = matches.isEmpty()
        resultsView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (isEmpty) {
            emptyTitleView.text = if (query.isBlank()) {
                "لا توجد موارد في هذه الفئة"
            } else {
                "لا توجد نتائج مطابقة لـ «${query.trim()}»"
            }
        }
    }

    private fun deliverSelection(metadata: ArchitecturalAssetMetadata) {
        selectedAssetId = metadata.id
        adapter.select(metadata.id)
        parentFragmentManager.setFragmentResult(
            RESULT_KEY,
            Bundle().apply { putString(RESULT_ASSET_ID, metadata.id) }
        )
        selectionCallback?.invoke(metadata)
        dismiss()
    }

    private class ArchitecturalAssetAdapter(
        initialSelectedAssetId: String?,
        private val onAssetClick: (ArchitecturalAssetMetadata) -> Unit
    ) : RecyclerView.Adapter<ArchitecturalAssetAdapter.AssetViewHolder>() {

        private var assets: List<ArchitecturalAssetMetadata> = emptyList()
        private var selectedAssetId: String? = initialSelectedAssetId

        init {
            setHasStableIds(true)
        }

        fun submitAssets(newAssets: List<ArchitecturalAssetMetadata>) {
            assets = newAssets
            notifyDataSetChanged()
        }

        fun select(assetId: String) {
            if (selectedAssetId == assetId) return
            val previousIndex = assets.indexOfFirst { it.id == selectedAssetId }
            selectedAssetId = assetId
            if (previousIndex >= 0) notifyItemChanged(previousIndex)
            val selectedIndex = assets.indexOfFirst { it.id == assetId }
            if (selectedIndex >= 0) notifyItemChanged(selectedIndex)
        }

        override fun getItemId(position: Int): Long = assets[position].id.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetViewHolder {
            val context = parent.context
            val preview = ArchitecturalAssetPreviewView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(context, 88)
                )
            }
            val name = TextView(context).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(context, 8), 0, 0)
            }
            val details = TextView(context).apply {
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(context, 3), 0, 0)
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 10))
                addView(preview)
                addView(name)
                addView(details)
            }
            val card = MaterialCardView(context).apply {
                isClickable = true
                isFocusable = true
                radius = dp(context, 16).toFloat()
                cardElevation = dp(context, 1).toFloat()
                strokeWidth = dp(context, 1)
                minimumHeight = dp(context, 148)
                rippleColor = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.ripple_accent)
                )
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    val gap = dp(context, 5)
                    setMargins(gap, gap, gap, gap)
                }
            }
            return AssetViewHolder(card, preview, name, details)
        }

        override fun onBindViewHolder(holder: AssetViewHolder, position: Int) {
            val metadata = assets[position]
            val selected = metadata.id == selectedAssetId
            holder.preview.asset = metadata
            holder.preview.isAssetSelected = selected
            holder.name.text = metadata.nameAr
            holder.details.text = dimensionsLabel(metadata)
            holder.card.isSelected = selected
            holder.card.setCardBackgroundColor(
                ContextCompat.getColor(
                    holder.card.context,
                    if (selected) R.color.surface_3 else R.color.surface_2
                )
            )
            holder.card.strokeColor = ContextCompat.getColor(
                holder.card.context,
                if (selected) R.color.procreate_accent_light else R.color.outline_subtle
            )
            holder.card.contentDescription = buildString {
                append(metadata.nameAr)
                append("، ")
                append(categoryLabel(metadata.category))
                append("، ")
                append(holder.details.text)
                if (selected) append("، محدد حاليًا")
            }
            holder.card.setOnClickListener { onAssetClick(metadata) }
        }

        override fun getItemCount(): Int = assets.size

        private fun dimensionsLabel(metadata: ArchitecturalAssetMetadata): String {
            val formatter = DecimalFormat("0.#")
            val width = formatter.format(metadata.defaultDimensionsMeters.width)
            val height = formatter.format(metadata.defaultDimensionsMeters.height)
            return "$width × $height م"
        }

        private fun categoryLabel(category: ArchitecturalAssetCategory): String = when (category) {
            ArchitecturalAssetCategory.TREES -> "الأشجار"
            ArchitecturalAssetCategory.PEOPLE -> "الأشخاص"
            ArchitecturalAssetCategory.VEHICLES -> "المركبات"
        }

        class AssetViewHolder(
            val card: MaterialCardView,
            val preview: ArchitecturalAssetPreviewView,
            val name: TextView,
            val details: TextView
        ) : RecyclerView.ViewHolder(card)
    }

    companion object {
        const val RESULT_KEY = "architectural_asset_picker_result"
        const val RESULT_ASSET_ID = "asset_id"

        private const val ARG_SELECTED_ASSET_ID = "selected_asset_id"
        private const val STATE_SELECTED_ASSET_ID = "state_selected_asset_id"
        private const val STATE_QUERY = "state_query"
        private const val STATE_CATEGORY = "state_category"

        /**
         * Opens the picker and returns the selected catalog record. Example:
         *
         * `ArchitecturalAssetPickerDialog.show(supportFragmentManager) { asset -> ... }`
         */
        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            initiallySelectedAssetId: String? = null,
            onAssetSelected: (ArchitecturalAssetMetadata) -> Unit
        ): ArchitecturalAssetPickerDialog {
            return ArchitecturalAssetPickerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_SELECTED_ASSET_ID, initiallySelectedAssetId)
                }
                setOnAssetSelectedListener(onAssetSelected)
                show(fragmentManager, TAG)
            }
        }

        private const val TAG = "ArchitecturalAssetPickerDialog"

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }
}
