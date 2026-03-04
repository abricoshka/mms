package com.goodwy.commons.adapters

import android.annotation.SuppressLint
import android.view.*
import android.widget.ImageView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.ActionMenuView
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.R
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.CONTACT_THUMBNAILS_SIZE_EXTRA_LARGE
import com.goodwy.commons.helpers.CONTACT_THUMBNAILS_SIZE_LARGE
import com.goodwy.commons.helpers.CONTACT_THUMBNAILS_SIZE_SMALL
import com.goodwy.commons.interfaces.MyActionModeCallback
import com.goodwy.commons.views.BottomPaddingDecoration
import com.goodwy.commons.views.CustomActionModeToolbar
import com.goodwy.commons.views.MyDividerDecoration
import com.goodwy.commons.views.MyRecyclerView
import kotlin.math.max
import kotlin.math.min

abstract class MyRecyclerViewAdapter(
    val activity: BaseSimpleActivity,
    val recyclerView: MyRecyclerView,
    val itemClick: (Any) -> Unit,
) :
    RecyclerView.Adapter<MyRecyclerViewAdapter.ViewHolder>() {
    protected val baseConfig = activity.baseConfig
    protected val resources = activity.resources!!
    protected val layoutInflater = activity.layoutInflater
    protected var accentColor = activity.getProperAccentColor()
    protected var textColor = activity.getProperTextColor()
    protected var backgroundColor = activity.getProperBackgroundColor()
    protected var surfaceColor = activity.getSurfaceColor()
    protected var properPrimaryColor = activity.getProperPrimaryColor()
    protected var contrastColor = properPrimaryColor.getContrastColor()
    protected var contactThumbnailsSize = contactThumbnailsSize()
    protected var actModeCallback: MyActionModeCallback
    protected var selectedKeys = LinkedHashSet<Int>()
    protected var positionOffset = 0
    protected var actMode: ActionMode? = null

    protected var actBarToolbar: CustomActionModeToolbar? = null
    private var lastLongPressedItem = -1
    private var originalStatusBarColor: Int? = null

    private var isDividersVisible = false
    private var dividerDecoration: MyDividerDecoration? = null
    private var bottomPaddingDecoration: BottomPaddingDecoration? = null

    abstract fun getActionMenuId(): Int

    abstract fun prepareActionMode(menu: Menu)

    abstract fun actionItemPressed(id: Int)

    abstract fun getSelectableItemCount(): Int

    abstract fun getIsItemSelectable(position: Int): Boolean

    abstract fun getItemSelectionKey(position: Int): Int?

    abstract fun getItemKeyPosition(key: Int): Int

    abstract fun onActionModeCreated()

    abstract fun onActionModeDestroyed()

    protected fun isOneItemSelected() = selectedKeys.size == 1

    init {
        actModeCallback = object : MyActionModeCallback() {
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                actionItemPressed(item.itemId)
                return true
            }

            @SuppressLint("UseCompatLoadingForDrawables")
            override fun onCreateActionMode(actionMode: ActionMode, menu: Menu?): Boolean {
                if (getActionMenuId() == 0) {
                    return true
                }

                selectedKeys.clear()
                isSelectable = true
                actMode = actionMode
                
                // Create custom action mode toolbar
                actBarToolbar = CustomActionModeToolbar.create(
                    context = activity,
                    title = "",
                    onTitleClick = {
                        if (getSelectableItemCount() == selectedKeys.size) {
                            finishActMode()
                        } else {
                            selectAll()
                        }
                    }
                )
                // Set layout params BEFORE setting as customView to ensure full width
                val screenWidth = resources.displayMetrics.widthPixels
                actBarToolbar!!.layoutParams = ViewGroup.LayoutParams(
                    screenWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Now set as custom view
                actMode!!.customView = actBarToolbar

                // Don't inflate menu into ActionMode's menu - only use CustomActionModeToolbar's menu
                // activity.menuInflater.inflate(getActionMenuId(), menu) // Removed - using only custom toolbar menu
                
                // Inflate menu into CustomActionModeToolbar's menu (for popup menu)
                actBarToolbar!!.inflateMenu(getActionMenuId())
                
                // Set up menu item click listener to forward to ActionMode callback
                actBarToolbar!!.setOnMenuItemClickListener { item ->
                    actionItemPressed(item.itemId)
                    true
                }
                
                // Set up navigation icon (close button) for the custom toolbar
                val closeIcon = resources.getDrawable(R.drawable.ic_chevron_left_vector, activity.theme)
                actBarToolbar!!.navigationIcon = closeIcon
                actBarToolbar!!.setNavigationOnClickListener {
                    finishActMode()
                }
                actBarToolbar!!.setNavigationContentDescription(android.R.string.cancel)

//                val cabBackgroundColor = if (activity.isDynamicTheme()) {
//                    resources.getColor(R.color.you_background_color, activity.theme)
//                } else {
//                    activity.getColoredMaterialStatusBarColor()
//                }
                val cabBackgroundColor = activity.getSurfaceColor()
                val cabContrastColor = cabBackgroundColor.getContrastColor()
                val actModeBar = actMode!!.customView?.parent as? View
                actModeBar?.setBackgroundColor(cabBackgroundColor)

                actBarToolbar!!.updateTextColorForBackground(cabBackgroundColor)
                actBarToolbar!!.updateColorsForBackground(cabBackgroundColor)
                // Don't update ActionMode menu colors - we're not using ActionMode's menu
                // activity.updateMenuItemColors(menu, baseColor = cabBackgroundColor, forceWhiteIcons = false)
                
                // Update status bar color to match action mode toolbar
                if (activity is com.goodwy.commons.activities.EdgeToEdgeActivity) {
                    originalStatusBarColor = activity.window.statusBarColor
                    activity.window.statusBarColor = cabBackgroundColor
                    activity.window.setSystemBarsAppearance(cabBackgroundColor)
                }
                
                onActionModeCreated()
                
                // Ensure full width and hide default close button
                actBarToolbar?.onGlobalLayout {
                    // Hide ActionMode's default close button
                    val defaultCloseButton = activity.findViewById<View>(androidx.appcompat.R.id.action_mode_close_button)
                    defaultCloseButton?.visibility = View.GONE
                    
                    // Find and modify all parent containers up to the root
                    var currentParent: ViewParent? = actBarToolbar?.parent
                    while (currentParent != null && currentParent is ViewGroup) {
                        val parentView = currentParent as ViewGroup
                        val params = parentView.layoutParams
                        if (params != null) {
                            params.width = ViewGroup.LayoutParams.MATCH_PARENT
                            parentView.layoutParams = params
                        }
                        // Remove padding from parent containers to match CustomToolbar behavior
                        parentView.setPadding(0, parentView.paddingTop, 0, parentView.paddingBottom)
                        currentParent = parentView.parent
                    }
                    
                    // Force custom toolbar to full width
                    val params = actBarToolbar?.layoutParams
                    if (params != null) {
                        params.width = ViewGroup.LayoutParams.MATCH_PARENT
                        actBarToolbar?.layoutParams = params
                    }
                    actBarToolbar?.requestLayout()
                }
                
                return true
            }

            override fun onPrepareActionMode(actionMode: ActionMode, menu: Menu): Boolean {
                // Prepare the menu in CustomActionModeToolbar instead of ActionMode's menu
                // We need to sync menu state, so we'll prepare it in the custom toolbar's menu
                actBarToolbar?.menu?.let { customMenu ->
                    prepareActionMode(customMenu)
                    actBarToolbar?.invalidateMenu()
                }
                return true
            }

            override fun onDestroyActionMode(actionMode: ActionMode) {
                isSelectable = false
                (selectedKeys.clone() as HashSet<Int>).forEach {
                    val position = getItemKeyPosition(it)
                    if (position != -1) {
                        toggleItemSelection(false, position, false)
                    }
                }

                updateTitle()
                selectedKeys.clear()
                actBarToolbar?.title = ""
                
                // Restore status bar color using activity's method to avoid flash
                if (activity is com.goodwy.commons.activities.EdgeToEdgeActivity) {
                    // Post the restoration to avoid flash - let UI settle first
                    activity.window.decorView.post {
                        val useSurfaceColor = activity.isDynamicTheme() && !activity.isSystemInDarkMode()
                        val statusBarColor = activity.getStartRequiredStatusBarColor()
                        activity.window.statusBarColor = statusBarColor
                        activity.window.setSystemBarsAppearance(statusBarColor)
                    }
                    originalStatusBarColor = null
                }
                
                actMode = null
                lastLongPressedItem = -1
                onActionModeDestroyed()
            }
        }
    }

    protected fun toggleItemSelection(select: Boolean, pos: Int, updateTitle: Boolean = true) {
        if (select && !getIsItemSelectable(pos)) {
            return
        }

        val itemKey = getItemSelectionKey(pos) ?: return
        if ((select && selectedKeys.contains(itemKey)) || (!select && !selectedKeys.contains(itemKey))) {
            return
        }

        if (select) {
            selectedKeys.add(itemKey)
        } else {
            selectedKeys.remove(itemKey)
        }

        notifyItemChanged(pos + positionOffset)

        if (updateTitle) {
            updateTitle()
        }

        // Don't finish action mode when all items are deselected
        // if (selectedKeys.isEmpty()) {
        //     finishActMode()
        // }
    }

    fun updateTitle() {
        val selectableItemCount = getSelectableItemCount()
        val selectedCount = min(selectedKeys.size, selectableItemCount)
        val oldTitle = actBarToolbar?.title
        val newTitle = "$selectedCount / $selectableItemCount"
        if (oldTitle != newTitle) {
            actBarToolbar?.title = newTitle
            actMode?.invalidate()
        }
        
        // Update select all button icon based on selection state
        // Note: Subclasses should override this to provide the correct menu item ID
        updateSelectAllButtonIconIfAvailable(selectableItemCount, selectedCount)
    }
    
    /**
     * Updates the select all button icon if the menu item ID is available.
     * Subclasses can override this to provide the correct menu item ID.
     */
    protected open fun updateSelectAllButtonIconIfAvailable(selectableItemCount: Int, selectedCount: Int) {
        // Try to find select all menu item by title
        val allSelected = selectableItemCount > 0 && selectedCount == selectableItemCount
        actBarToolbar?.menu?.let { menu ->
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                if (item?.title?.toString()?.contains("select", ignoreCase = true) == true ||
                    item?.title?.toString()?.contains("全选", ignoreCase = false) == true ||
                    item?.title?.toString()?.contains("전체", ignoreCase = false) == true) {
                    actBarToolbar?.updateSelectAllButtonIcon(item.itemId, allSelected)
                    break
                }
            }
        }
    }

    fun itemLongClicked(position: Int) {
        recyclerView.setDragSelectActive(position)
        lastLongPressedItem = if (lastLongPressedItem == -1) {
            position
        } else {
            val min = min(lastLongPressedItem, position)
            val max = max(lastLongPressedItem, position)
            for (i in min..max) {
                toggleItemSelection(true, i, false)
            }
            updateTitle()
            position
        }
    }

    protected fun getSelectedItemPositions(sortDescending: Boolean = true): ArrayList<Int> {
        val positions = ArrayList<Int>()
        val keys = selectedKeys.toList()
        keys.forEach {
            val position = getItemKeyPosition(it)
            if (position != -1) {
                positions.add(position)
            }
        }

        if (sortDescending) {
            positions.sortDescending()
        }
        return positions
    }

    protected open fun selectAll() {
        val cnt = itemCount - positionOffset
        for (i in 0 until cnt) {
            toggleItemSelection(true, i, false)
        }
        lastLongPressedItem = -1
        updateTitle()
    }

    protected fun setupDragListener(enable: Boolean) {
        if (enable) {
            recyclerView.setupDragListener(object : MyRecyclerView.MyDragListener {
                override fun selectItem(position: Int) {
                    toggleItemSelection(true, position, true)
                }

                override fun selectRange(initialSelection: Int, lastDraggedIndex: Int, minReached: Int, maxReached: Int) {
                    selectItemRange(
                        initialSelection,
                        max(0, lastDraggedIndex - positionOffset),
                        max(0, minReached - positionOffset),
                        maxReached - positionOffset
                    )
                    if (minReached != maxReached) {
                        lastLongPressedItem = -1
                    }
                }
            })
        } else {
            recyclerView.setupDragListener(null)
        }
    }

    protected fun selectItemRange(from: Int, to: Int, min: Int, max: Int) {
        if (from == to) {
            (min..max).filter { it != from }.forEach { toggleItemSelection(false, it, true) }
            return
        }

        if (to < from) {
            for (i in to..from) {
                toggleItemSelection(true, i, true)
            }

            if (min > -1 && min < to) {
                (min until to).filter { it != from }.forEach { toggleItemSelection(false, it, true) }
            }

            if (max > -1) {
                for (i in from + 1..max) {
                    toggleItemSelection(false, i, true)
                }
            }
        } else {
            for (i in from..to) {
                toggleItemSelection(true, i, true)
            }

            if (max > -1 && max > to) {
                (to + 1..max).filter { it != from }.forEach { toggleItemSelection(false, it, true) }
            }

            if (min > -1) {
                for (i in min until from) {
                    toggleItemSelection(false, i, true)
                }
            }
        }
    }

    fun setupZoomListener(zoomListener: MyRecyclerView.MyZoomListener?) {
        recyclerView.setupZoomListener(zoomListener)
    }

//    fun addVerticalDividers(add: Boolean) {
//        if (recyclerView.itemDecorationCount > 0) {
//            recyclerView.removeItemDecorationAt(0)
//        }
//
//        if (add) {
//            DividerItemDecoration(activity, DividerItemDecoration.VERTICAL).apply {
//                ContextCompat.getDrawable(activity, R.drawable.divider)?.let {
//                    setDrawable(it)
//                }
//                recyclerView.addItemDecoration(this)
//            }
//        }
//    }

    /**
     * Vertical separator between elements.
     *
     * @param visible Enabled.
     * @param paddingStartDp Left padding in Dp.
     * @param paddingEndDp Right padding in Dp.
     * @param dividerHeightDp Height of the divider in Dp.
     */
    fun setVerticalDividers(
        visible: Boolean,
        paddingStartDp: Int = 0,
        paddingEndDp: Int = 0,
        dividerHeightDp: Int = 1,
        color: Int = 0x33AAAAAA, // activity.getDividerColor(),
    ) {
        // Remove the old separator
        dividerDecoration?.let {
            recyclerView.removeItemDecoration(it)
            dividerDecoration = null
        }

        if (visible) {
            // Create or reuse a separator
            val decoration = MyDividerDecoration().apply {
                setConfiguration(
                    paddingStartDp = paddingStartDp,
                    paddingEndDp = paddingEndDp,
                    dividerHeightDp = dividerHeightDp,
                    color = color,
                    context = activity
                )
                setVisible(true)
            }

            recyclerView.addItemDecoration(decoration)
            dividerDecoration = decoration
            isDividersVisible = true
        } else {
            isDividersVisible = false
        }
    }

    /**
     * Creates the bottom margin of the adapter.
     * If there is no scrollbar in the Adapter, it is better to use:
     * ```
     *         android:scrollbars=“vertical”
     *         android:clipToPadding=“false”
     *         android:paddingBottom=“128dp”
     * ```
     *
     * @param bottomPaddingDp Height of the setback in Dp.
     */
    fun addBottomPadding(bottomPaddingDp: Int) {
        // Remove the old indent if there is one
        bottomPaddingDecoration?.let {
            recyclerView.removeItemDecoration(it)
            bottomPaddingDecoration = null
        }

        if (bottomPaddingDp > 0) {
            // Convert dp to pixels
            val paddingPx = bottomPaddingDp.dpToPx(activity)

            // Create and add decoration
            BottomPaddingDecoration(paddingPx).apply {
                recyclerView.addItemDecoration(this)
                bottomPaddingDecoration = this
            }

            // Updating display
            recyclerView.invalidateItemDecorations()
        }
    }

    fun finishActMode() {
        actMode?.finish()
    }

    fun startActMode() {
        if (actMode == null && !actModeCallback.isSelectable) {
            activity.startActionMode(actModeCallback)
        }
    }

    fun updateTextColor(textColor: Int) {
        this.textColor = textColor
        notifyDataSetChanged()
    }

    fun updatePrimaryColor() {
        properPrimaryColor = activity.getProperPrimaryColor()
        contrastColor = properPrimaryColor.getContrastColor()
        accentColor = activity.getProperAccentColor()
    }

    fun updateBackgroundColor(backgroundColor: Int) {
        this.backgroundColor = backgroundColor
        surfaceColor = activity.getSurfaceColor()
        notifyDataSetChanged()
    }

    private fun contactThumbnailsSize(): Float {
        return when (activity.baseConfig.contactThumbnailsSize) {
            CONTACT_THUMBNAILS_SIZE_SMALL -> 0.9F
            CONTACT_THUMBNAILS_SIZE_LARGE -> 1.15F
            CONTACT_THUMBNAILS_SIZE_EXTRA_LARGE -> 1.3F
            else -> 1.0F
        }
    }

    protected fun createViewHolder(layoutType: Int, parent: ViewGroup?): ViewHolder {
        val view = layoutInflater.inflate(layoutType, parent, false)
        return ViewHolder(view)
    }

    protected fun createViewHolder(view: View): ViewHolder {
        return ViewHolder(view)
    }

    protected fun bindViewHolder(holder: ViewHolder) {
        holder.itemView.tag = holder
    }

    protected fun removeSelectedItems(positions: ArrayList<Int>) {
        positions.forEach {
            notifyItemRemoved(it)
        }
        finishActMode()
    }

    open inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bindView(any: Any, allowSingleClick: Boolean, allowLongClick: Boolean, callback: (itemView: View, adapterPosition: Int) -> Unit): View {
            return itemView.apply {
                callback(this, adapterPosition)

                if (allowSingleClick) {
                    setOnClickListener { viewClicked(any) }
                    setOnLongClickListener { if (allowLongClick) viewLongClicked() else viewClicked(any); true }
                } else {
                    setOnClickListener(null)
                    setOnLongClickListener(null)
                }
            }
        }

        fun viewClicked(any: Any) {
            if (actModeCallback.isSelectable) {
                val currentPosition = adapterPosition - positionOffset
                val isSelected = selectedKeys.contains(getItemSelectionKey(currentPosition))
                toggleItemSelection(!isSelected, currentPosition, true)
            } else {
                itemClick.invoke(any)
            }
            lastLongPressedItem = -1
        }

        fun viewLongClicked() {
            val currentPosition = adapterPosition - positionOffset
            if (!getIsItemSelectable(currentPosition)) return
            if (!actModeCallback.isSelectable) {
                activity.startActionMode(actModeCallback)
            }

            toggleItemSelection(true, currentPosition, true)
            itemLongClicked(currentPosition)
        }
    }

    // Cleaning resources
    fun cleanup() {
        dividerDecoration?.let {
            recyclerView.removeItemDecoration(it)
            dividerDecoration = null
        }
        MyDividerDecoration.clearCache()

        bottomPaddingDecoration?.let {
            recyclerView.removeItemDecoration(it)
            bottomPaddingDecoration = null
        }
    }
}
