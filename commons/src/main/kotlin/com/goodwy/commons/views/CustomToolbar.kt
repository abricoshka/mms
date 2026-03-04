package com.goodwy.commons.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.ContextCompat
import com.goodwy.commons.R
import com.goodwy.commons.databinding.CustomToolbarBinding
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getProperTextCursorColor
import com.goodwy.commons.extensions.onTextChangeListener

/**
 * Custom toolbar implementation using LinearLayout that mimics MaterialToolbar API
 * while preserving the same styling and functionality.
 */
class CustomToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var binding: CustomToolbarBinding? = null

    private var _menu: Menu? = null
    private var _action_menu: Menu? = null
    private var hasInflatedActionMenu = false
    private var menuInflater: MenuInflater? = null
    private var actionMenuInflater: MenuInflater? = null
    private var navigationMenu: Menu? = null
    private var navigationMenuInflater: MenuInflater? = null
    private var onMenuItemClickListener: MenuItem.OnMenuItemClickListener? = null
    private var onNavigationClickListener: OnClickListener? = null
    private var onNavigationMenuItemClickListener: MenuItem.OnMenuItemClickListener? = null
    private var onSearchTextChangedListener: ((String) -> Unit)? = null
    private var onSearchBackClickListener: OnClickListener? = null
    private var onSearchExpandListener: OnClickListener? = null
    private var onSearchClearClickListener: (() -> Unit)? = null
    private var forceShowSearchClearButton: Boolean = false

    // Cached values for performance
    private var cachedTextColor: Int? = null
    private var cachedPrimaryColor: Int? = null
    private var cachedCursorColor: Int? = null

    private var overflowIconDrawable: Drawable? = null
    private var navigationIconDrawable: Drawable? = null
    private var isSearchBound = false

    var isSearchExpanded: Boolean = false
        private set

    private fun navigationActionBarView(): View? {
        val currentBinding = binding ?: return null
        return currentBinding.root.findViewById(R.id.navigationIconView)
    }

    private fun navigationActionBarMenu(): Menu? {
        val actionBar = navigationActionBarView() ?: return null
        return runCatching {
            val method = actionBar.javaClass.getMethod("getMenu")
            method.invoke(actionBar) as? Menu
        }.getOrNull()
    }

    private fun bindNavigationActionBarClickListener() {
        val actionBar = navigationActionBarView() ?: return
        runCatching {
            val method = actionBar.javaClass.getMethod(
                "setOnMenuItemClickListener",
                MenuItem.OnMenuItemClickListener::class.java
            )
            val listener = MenuItem.OnMenuItemClickListener {
                onNavigationIconClicked(actionBar)
                true
            }
            method.invoke(actionBar, listener)
        }
    }

    private fun inflateNavigationIconViewMenu() {
        binding?.navigationIconView?.inflateMenu(R.menu.cab_navigation_only)
//        runCatching {
//            val menu = navigationActionBarMenu()
//            menu?.clear()
//            val method = actionBar.javaClass.getMethod("inflateMenu", Int::class.javaPrimitiveType)
//            method.invoke(actionBar, R.menu.cab_navigation_only)
//        }
    }

    private fun applyNavigationIconDrawable(drawable: Drawable?) {
        navigationIconDrawable = drawable?.mutate()
        val actionBarView = navigationActionBarView() ?: return
        val menu = navigationActionBarMenu()

        if (menu != null) {
            val fallbackItem = if (menu.size() > 0) menu.getItem(0) else null
            val navItem = menu.findItem(R.id.cab_remove) ?: fallbackItem
            if (navItem != null) {
                if (navigationIconDrawable != null) {
                    navItem.icon = navigationIconDrawable
                }
                navItem.isVisible = true
            }
        }

        // Show nav icon area when we have a drawable or when the menu has items (default back)
        val showNav = (menu?.size() ?: 0) > 0 || navigationIconDrawable != null
        actionBarView.visibility = if (showNav) View.VISIBLE else View.GONE
        bindNavigationActionBarClickListener()
        updateTitleMargin(actionBarView.visibility == View.VISIBLE)
    }

    private fun onNavigationIconClicked(view: View) {
        if (dispatchNavigationMenuItemClick()) {
            return
        }
        onNavigationClickListener?.onClick(view)
    }

    private fun dispatchNavigationMenuItemClick(): Boolean {
        val currentMenu = navigationMenu ?: return false
        for (i in 0 until currentMenu.size()) {
            val item = currentMenu.getItem(i)
            if (item.isVisible) {
                return onNavigationMenuItemClickListener?.onMenuItemClick(item) ?: false
            }
        }
        return false
    }

    var navigationIcon: Drawable?
        get() = navigationIconDrawable
        set(value) {
            applyNavigationIconDrawable(value)
        }

    private fun updateTitleMargin(hasNavigationIcon: Boolean) {
        binding?.titleTextView?.let { titleView ->
            val layoutParams = titleView.layoutParams as? android.widget.RelativeLayout.LayoutParams
            if (layoutParams != null) {
                val marginStart = if (hasNavigationIcon) {
                    // Use larger margin when navigation icon is visible for better spacing
                    // Combine activity_margin (16dp) + normal_margin (12dp) = 28dp for more padding
                    resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin) +
                    resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.normal_margin)
                } else {
                    resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.normal_margin)
                }
                layoutParams.marginStart = marginStart
                titleView.layoutParams = layoutParams
            }
        }
    }

    var title: CharSequence?
        get() = binding?.titleTextView?.text
        set(value) {
            binding?.titleTextView?.apply {
                text = value
                visibility = if (value != null && value.isNotEmpty()) View.VISIBLE else View.GONE
            }
            // Update title margin when title is set
            val hasNavigationIcon = navigationActionBarView()?.visibility == View.VISIBLE
            updateTitleMargin(hasNavigationIcon)
        }

    val menu: Menu
        get() {
            if (_menu == null) {
                _menu = MenuBuilder(context)
            }
            return _menu!!
        }
    val actionMenu: Menu
        get() {
            if (_action_menu == null) {
                _action_menu = MenuBuilder(context)
            }
            return _action_menu!!
        }

    var overflowIcon: Drawable?
        get() = overflowIconDrawable
        set(value) {
            overflowIconDrawable = value
        }

    var collapseIcon: Drawable? = null

    init {
        // Inflate toolbar layout using ViewBinding
        val toolbarBinding = CustomToolbarBinding.inflate(LayoutInflater.from(context), this, false)
        binding = toolbarBinding

        // Copy layout parameters and properties from inflated layout
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
//        setPadding(
//            toolbarBinding.root.paddingLeft,
//            toolbarBinding.root.paddingTop,
//            toolbarBinding.root.paddingRight,
//            toolbarBinding.root.paddingBottom
//        )

        // Add the root view to this CustomToolbar
        addView(toolbarBinding.root)
        setupActionBarAndSearchBindings()

        // Read attributes from XML
        attrs?.let {
            val attrsArray = intArrayOf(
                android.R.attr.title,
                androidx.appcompat.R.attr.menu
            )
            val typedArray = context.obtainStyledAttributes(it, attrsArray)

            // Read title
            val titleRes = typedArray.getResourceId(0, 0)
            if (titleRes != 0) {
                title = context.getString(titleRes)
            } else {
                val titleText = typedArray.getString(0)
                if (!titleText.isNullOrEmpty()) {
                    title = titleText
                }
            }

            // Read menu resource
            val menuRes = typedArray.getResourceId(1, 0)
            if (menuRes != 0) {
                post { inflateMenu(menuRes) }
            }

            typedArray.recycle()
        }

        // Read navigationIcon from XML (e.g. app:navigationIcon) and apply after layout is ready
        attrs?.let {
            runCatching {
                val toolbarAttrs = context.obtainStyledAttributes(it, androidx.appcompat.R.styleable.Toolbar, 0, 0)
                val navIconDrawable = toolbarAttrs.getDrawable(androidx.appcompat.R.styleable.Toolbar_navigationIcon)
                toolbarAttrs.recycle()
                if (navIconDrawable != null) {
                    post { navigationIcon = navIconDrawable }
                }
            }
        }
    }

    private fun getTextColor(): Int {
        if (cachedTextColor == null) {
            cachedTextColor = context.getProperTextColor()
        }
        return cachedTextColor!!
    }

    private fun getPrimaryColor(): Int {
        if (cachedPrimaryColor == null) {
            cachedPrimaryColor = context.getProperPrimaryColor()
        }
        return cachedPrimaryColor!!
    }

    private fun getCursorColor(): Int {
        if (cachedCursorColor == null) {
            cachedCursorColor = context.getProperTextCursorColor()
        }
        return cachedCursorColor!!
    }

    private fun setupActionBarAndSearchBindings() {
        val binding = binding ?: return

        inflateNavigationIconViewMenu()
        applyNavigationIconDrawable(null)
        binding.actionBar.setPosition("right")
        bindSearchCallbacks()
        bindActionBarMenuListener()
        syncMenuFromActionBar()
        updateMenuDisplay()
    }

    private fun bindSearchCallbacks() {
        if (isSearchBound) return
        val binding = binding ?: return
        val queryView = binding.actionBarSearch.searchEditText

        // Search query text callback
        queryView.onTextChangeListener { text ->
            updateSearchClearIconVisibility(text)
            if (isSearchExpanded) {
                onSearchTextChangedListener?.invoke(text)
            }
        }

        queryView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val endDrawable = queryView.compoundDrawablesRelative[2]
                if (endDrawable != null) {
                    val drawableWidth = if (endDrawable.bounds.width() > 0) {
                        endDrawable.bounds.width()
                    } else {
                        endDrawable.intrinsicWidth
                    }
                    val touchStart = queryView.width - queryView.paddingEnd - drawableWidth
                    if (event.x >= touchStart) {
                        if (queryView.text?.isNotEmpty() == true) {
                            queryView.setText("")
                        } else if (forceShowSearchClearButton) {
                            onSearchClearClickListener?.invoke()
                        }
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        // Use clear action as the explicit "close search" action
        binding.actionBarSearch.searchBackButton.setOnClickListener {
            collapseSearch()
            onSearchBackClickListener?.onClick(it)
        }

        updateSearchClearIconVisibility(queryView.text?.toString().orEmpty())
        isSearchBound = true
    }

    private fun updateSearchClearIconVisibility(text: String) {
        val binding = binding ?: return
        val queryView = binding.actionBarSearch.searchEditText
        val showClear = text.isNotEmpty() || forceShowSearchClearButton
        queryView.setCompoundDrawablesRelativeWithIntrinsicBounds(
            com.android.common.R.drawable.ic_cmn_search,
            0,
            if (showClear) com.android.common.R.drawable.ic_cmn_circle_close_fill else 0,
            0
        )
    }

    private fun bindActionBarMenuListener() {
        val binding = binding ?: return
        binding.actionBar.setOnMenuItemClickListener { menuItem ->
            val handledByClient = onMenuItemClickListener?.onMenuItemClick(menuItem) ?: false
            if (handledByClient) {
                true
            } else if (isSearchMenuItem(menuItem.itemId)) {
                expandSearch()
                true
            } else {
                false
            }
        }
    }

    private fun isSearchMenuItem(itemId: Int): Boolean {
        val entryName = runCatching { resources.getResourceEntryName(itemId) }.getOrNull()
        return entryName == "search" || entryName == "action_search"
    }

    private fun syncMenuFromActionBar() {
        val actionBar = binding?.actionBar ?: return
        val reflectedMenu = runCatching {
            val method = actionBar.javaClass.getMethod("getMenu")
            method.invoke(actionBar) as? Menu
        }.getOrNull()

        if (reflectedMenu != null && (_action_menu == null || !hasInflatedActionMenu)) {
            _action_menu = reflectedMenu
        }
    }

    private fun hasVisibleMenuItems(menu: Menu?): Boolean {
        val currentMenu = menu ?: return false
        for (i in 0 until currentMenu.size()) {
            if (currentMenu.getItem(i).isVisible) {
                return true
            }
        }
        return false
    }

    fun setOnSearchTextChangedListener(listener: ((String) -> Unit)?) {
        onSearchTextChangedListener = listener
        bindSearchCallbacks()
    }

    fun setOnSearchBackClickListener(listener: OnClickListener?) {
        onSearchBackClickListener = listener
        bindSearchCallbacks()
    }

    fun setOnSearchClearClickListener(listener: (() -> Unit)?) {
        onSearchClearClickListener = listener
        bindSearchCallbacks()
    }

    fun setForceShowSearchClearButton(forceShow: Boolean) {
        forceShowSearchClearButton = forceShow
        val currentText = binding?.actionBarSearch?.searchEditText?.text?.toString().orEmpty()
        updateSearchClearIconVisibility(currentText)
    }

    fun setOnSearchExpandListener(listener: OnClickListener?) {
        onSearchExpandListener = listener
    }

    fun expandSearch() {
        val binding = binding ?: return
        if (isSearchExpanded) return

        isSearchExpanded = true
        binding.actionBarSearch.root.visibility = View.VISIBLE
        binding.actionBar.visibility = View.GONE
        binding.titleTextView.visibility = View.GONE
        updateSearchClearIconVisibility(binding.actionBarSearch.searchEditText.text?.toString().orEmpty())
        // Only autofocus editable search input to avoid opening IME in read-only states.
        if (isSearchInputEditable()) {
            // Post focus to the next frame so it consistently works after visibility/layout updates.
            binding.actionBarSearch.searchEditText.post {
                focusSearchInput()
            }
        }
        binding.actionBarSearch.searchBackButton.visibility = View.VISIBLE
        onSearchExpandListener?.onClick(this)
    }

    fun collapseSearch() {
        val binding = binding ?: return
        if (!isSearchExpanded && binding.actionBarSearch.root.visibility != View.VISIBLE) return

        isSearchExpanded = false
        forceShowSearchClearButton = false
        val queryView = binding.actionBarSearch.searchEditText
        queryView.setText("")
        queryView.clearFocus()
        updateSearchClearIconVisibility("")
        binding.actionBarSearch.searchBackButton.visibility = View.GONE
        binding.actionBarSearch.root.visibility = View.GONE
        updateMenuDisplay()
        binding.titleTextView.visibility =
            if (binding.titleTextView.text?.isNotEmpty() == true) View.VISIBLE else View.GONE

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    fun getSearchText(): String {
        val binding = binding ?: return ""
        return binding.actionBarSearch.searchEditText.text?.toString().orEmpty()
    }

    fun setSearchText(text: String) {
        val binding = binding ?: return
        binding.actionBarSearch.searchEditText.setText(text)
        updateSearchClearIconVisibility(text)
    }

    fun setSearchHint(hint: CharSequence?) {
        val binding = binding ?: return
        binding.actionBarSearch.searchEditText.hint = hint
    }

    fun resetSearchHint() {
        setSearchHint(context.getString(R.string.search))
    }

    fun setSearchInputEditable(isEditable: Boolean) {
        val binding = binding ?: return
        binding.actionBarSearch.searchEditText.apply {
            isFocusable = isEditable
            isFocusableInTouchMode = isEditable
            isCursorVisible = isEditable
            isLongClickable = isEditable
            setTextIsSelectable(isEditable)
        }
    }

    fun focusSearchInput(showKeyboard: Boolean = true) {
        val binding = binding ?: return
        if (!isSearchExpanded) {
            expandSearch()
        }
        if (!isSearchInputEditable()) {
            return
        }

        val searchEditText = binding.actionBarSearch.searchEditText
        searchEditText.requestFocus()
        searchEditText.setSelection(searchEditText.text?.length ?: 0)
        if (showKeyboard) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun isSearchInputEditable(): Boolean {
        val searchEditText = binding?.actionBarSearch?.searchEditText ?: return false
        return searchEditText.isEnabled && searchEditText.isFocusable && searchEditText.isFocusableInTouchMode
    }

    fun updateSearchColors() {
        cachedTextColor = null
        cachedPrimaryColor = null
        cachedCursorColor = null

        val binding = binding ?: return
        val textColor = getTextColor()
        val primaryColor = getPrimaryColor()
        val cursorColor = getCursorColor()

        binding.actionBarSearch.searchEditText.setColors(
            textColor,
            primaryColor,
            cursorColor
        )

        binding.actionBarSearch.searchBackButton.imageTintList =
            ColorStateList.valueOf(textColor)
    }

    fun setNavigationContentDescription(resId: Int) {
        navigationActionBarView()?.contentDescription = context.getString(resId)
    }

    fun setNavigationContentDescription(description: CharSequence?) {
        navigationActionBarView()?.contentDescription = description
    }

    fun setNavigationOnClickListener(listener: OnClickListener?) {
        onNavigationClickListener = listener
        bindNavigationActionBarClickListener()
    }

    fun inflateNavigationMenu(resId: Int) {
        if (navigationMenuInflater == null) {
            navigationMenuInflater = MenuInflater(context)
        }
        if (navigationMenu == null) {
            navigationMenu = MenuBuilder(context)
        }
        navigationMenu?.clear()
        navigationMenuInflater?.inflate(resId, navigationMenu)
        bindNavigationActionBarClickListener()
    }

    fun setOnNavigationMenuItemClickListener(listener: MenuItem.OnMenuItemClickListener?) {
        onNavigationMenuItemClickListener = listener
    }

    fun setTitleTextColor(color: Int) {
        binding?.titleTextView?.setTextColor(color)
    }

    fun setTitleTextColor(colors: ColorStateList?) {
        binding?.titleTextView?.setTextColor(colors)
    }

    fun getActionBar() = binding?.actionBar
    fun setActionBarVisibility(visible: Boolean) {
        val toolbarBinding = binding ?: return
        toolbarBinding.actionBar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun getLiveActionBarMenu(): Menu? {
        val actionBar = binding?.actionBar ?: return null
        return runCatching {
            val method = actionBar.javaClass.getMethod("getMenu")
            method.invoke(actionBar) as? Menu
        }.getOrNull()
    }

    private fun invalidateActionBarMenuPresentation() {
        val actionBar = binding?.actionBar ?: return
        val liveMenu = getLiveActionBarMenu()
        if (liveMenu is MenuBuilder) {
            liveMenu.onItemsChanged(true)
        }
        (actionBar as? View)?.let { actionBarView ->
            actionBarView.requestLayout()
            actionBarView.invalidate()
        }
    }

    fun setActionMenuItemVisibility(itemId: Int, isVisible: Boolean): Boolean {
        val liveMenu = getLiveActionBarMenu()
        if (liveMenu != null) {
            val targetItem = liveMenu.findItem(itemId) ?: return false
            if (targetItem.isVisible != isVisible) {
                targetItem.isVisible = isVisible
                invalidateActionBarMenuPresentation()
            }
            _action_menu?.findItem(itemId)?.let { modelItem ->
                if (modelItem.isVisible != isVisible) {
                    modelItem.isVisible = isVisible
                    if (_action_menu is MenuBuilder) {
                        (_action_menu as MenuBuilder).onItemsChanged(true)
                    }
                }
            }
            updateMenuDisplay()
            return true
        }

        val fallbackMenu = _action_menu ?: return false
        val fallbackItem = fallbackMenu.findItem(itemId) ?: return false
        if (fallbackItem.isVisible != isVisible) {
            fallbackItem.isVisible = isVisible
            if (fallbackMenu is MenuBuilder) {
                fallbackMenu.onItemsChanged(true)
            }
            updateMenuDisplay()
        }
        return true
    }

    fun setPopupForMoreItem(
        moreItemId: Int,
        menuResId: Int,
        blurTargetView: View?,
        listener: MenuItem.OnMenuItemClickListener?
    ): Boolean {
        val actionBar = binding?.actionBar ?: return false
        if (listener == null) return false

        // Keep popup menu in toolbar cache, so callers can mutate visibility/title dynamically.
        if (menuInflater == null) {
            menuInflater = MenuInflater(context)
        }
        if (_menu == null) {
            _menu = MenuBuilder(context)
        } else {
            _menu?.clear()
        }
        menuInflater?.inflate(menuResId, _menu)

        return runCatching {
            val setClickMethod = actionBar.javaClass.getMethod(
                "setOnMenuItemClickListener",
                MenuItem.OnMenuItemClickListener::class.java
            )
            val popupAnchor = actionBar as? View ?: return false
            val fallbackClickListener = MenuItem.OnMenuItemClickListener { clickedItem ->
                if (clickedItem.itemId == moreItemId) {
                    showMorePopupMenu(popupAnchor, listener)
                    true
                } else {
                    val handledByClient = onMenuItemClickListener?.onMenuItemClick(clickedItem) ?: false
                    if (handledByClient) {
                        true
                    } else if (isSearchMenuItem(clickedItem.itemId)) {
                        expandSearch()
                        true
                    } else {
                        false
                    }
                }
            }
            setClickMethod.invoke(actionBar, fallbackClickListener)
            true
        }.getOrDefault(false)
    }

    private fun showMorePopupMenu(
        anchor: View,
        listener: MenuItem.OnMenuItemClickListener
    ) {
        val sourceMenu = _menu ?: return
        val popupMenu = BlurPopupMenu(context, anchor, Gravity.END)
        for (i in 0 until sourceMenu.size()) {
            val item = sourceMenu.getItem(i)
            val popupItem = popupMenu.menu.add(item.groupId, item.itemId, item.order, item.title)
            popupItem.icon = item.icon
            popupItem.isVisible = item.isVisible
            popupItem.isEnabled = item.isEnabled
            popupItem.isCheckable = item.isCheckable
            popupItem.isChecked = item.isChecked
        }
        popupMenu.setOnMenuItemClickListener(listener)
        popupMenu.show()
    }

    fun inflateMenu(actionMenuResId: Int) {
        val reflectedActionBarMenu = runCatching {
            val actionBar = binding?.actionBar
            val method = actionBar?.javaClass?.getMethod("getMenu")
            method?.invoke(actionBar) as? Menu
        }.getOrNull()
        reflectedActionBarMenu?.clear()

        binding?.actionBar?.inflateMenu(actionMenuResId)
        hasInflatedActionMenu = true
        syncMenuFromActionBar()

        if (actionMenuInflater == null) {
            actionMenuInflater = MenuInflater(context)
        }
        _action_menu = MenuBuilder(context).also { menu ->
            actionMenuInflater?.inflate(actionMenuResId, menu)
        }

        // Fallback for cases where action bar menu reflection is unavailable.
        if (reflectedActionBarMenu == null && _action_menu == null) {
            _action_menu = MenuBuilder(context)
            actionMenuInflater?.inflate(actionMenuResId, _action_menu)
        }
        updateMenuDisplay()
    }

    fun setOnMenuItemClickListener(listener: MenuItem.OnMenuItemClickListener?) {
        onMenuItemClickListener = listener
        bindActionBarMenuListener()
    }

    fun invalidateMenu() {
        syncMenuFromActionBar()
        invalidateActionBarMenuPresentation()
        updateMenuDisplay()
    }

    private fun updateMenuDisplay() {
        val toolbarBinding = binding ?: return
        if (isSearchExpanded) {
            toolbarBinding.actionBar.visibility = View.GONE
            return
        }

        if (!hasInflatedActionMenu) {
            toolbarBinding.actionBar.visibility = View.GONE
            return
        }

        val shouldShowActionBar = hasVisibleMenuItems(_action_menu)
        toolbarBinding.actionBar.visibility = if (shouldShowActionBar) View.VISIBLE else View.GONE
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Don't update menu display on every layout change to prevent infinite loops
        // Menu display is updated when menu is inflated or invalidated explicitly
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Clear cached values to prevent memory leaks
        cachedTextColor = null
        cachedPrimaryColor = null
        cachedCursorColor = null
    }
}
