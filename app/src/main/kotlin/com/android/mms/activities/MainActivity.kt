package com.android.mms.activities

import android.annotation.SuppressLint
import android.app.SearchManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.speech.RecognizerIntent
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuItemCompat
import androidx.recyclerview.widget.RecyclerView
import com.android.common.view.MSearchView
import com.android.common.view.MVSideFrame
import com.goodwy.commons.dialogs.PermissionRequiredDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.views.BlurAppBarLayout
import com.goodwy.commons.views.TwoFingerSlideGestureDetector
import com.android.mms.BuildConfig
import com.android.mms.R
import com.android.mms.adapters.ConversationsAdapter
import com.android.mms.adapters.SearchResultsAdapter
import com.android.mms.databinding.ActivityMainBinding
import com.android.mms.extensions.*
import com.android.mms.helpers.SEARCHED_MESSAGE_ID
import com.android.mms.helpers.THREAD_ID
import com.android.mms.helpers.THREAD_TITLE
import com.android.mms.helpers.whatsNewList
import com.android.mms.models.Conversation
import com.android.mms.models.Events
import com.android.mms.models.Message
import com.android.mms.models.SearchResult
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.*
import kotlin.text.toFloat

class MainActivity : SimpleActivity() {
    companion object {
        private const val SECRET_BOX_PACKAGE = "chonha.get.secret.number"
        private const val SECRET_NUMBER_EXTRA = "secret_number"
        private const val INVALID_CIPHER = -1
        private const val MIN_SWIPE_DISTANCE = 50f
    }

    override var isSearchBarEnabled = true

    private val MAKE_DEFAULT_APP_REQUEST = 1

    private var storedPrimaryColor = 0
    private var storedTextColor = 0
    private var storedBackgroundColor = 0
    private var storedFontSize = 0
    private var lastSearchedText = ""
    private var bus: EventBus? = null
    private var isSpeechToTextAvailable = false
    private var isSearchAlwaysShow = false
    private var isSearchOpen = false
    private var searchQuery = ""

    private var mSearchMenuItem: MenuItem? = null
    private var mSearchView: SearchView? = null
    private lateinit var twoFingerGestureDetector: TwoFingerSlideGestureDetector
    private var pendingThreadIdsToEncrypt: LongArray? = null
    private var shouldExitSecureModeOnResume = false
    private var isLaunchingSecretBox = false

    private val binding by viewBinding(ActivityMainBinding::inflate)

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initTheme()
        setupTwoFingerSwipeGesture()
        initMVSideFrames()
        initBouncy()
        initBouncyListener()
        makeSystemBarsToTransparent()
        val isFirstLaunch = baseConfig.appRunCount == 0
        appLaunched(BuildConfig.APPLICATION_ID)
        // Initialize default quick texts if they haven't been initialized yet
        // The function internally checks if quick texts are empty to prevent re-initialization
        // if user deletes all quick texts later
        if (!config.quickTextsDefaultsInitialized) {
            config.initializeDefaultQuickTexts()
        }
        setupOptionsMenu()
        refreshMenuItemsAndTitle()
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.conversationsList, binding.searchResultsList))
        if (config.changeColourTopBar) {
            val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
            setupSearchMenuScrollListener(
                scrollingView = binding.conversationsList,
                searchMenu = binding.mainMenu,
                surfaceColor = useSurfaceColor
            )
        }
        checkWhatsNewDialog()
        storeStateVariables()

        binding.mainMenu.apply {
            searchBeVisibleIf(isSearchAlwaysShow) //hide top search bar
        }

        checkAndDeleteOldRecycleBinMessages()
        clearAllMessagesIfNeeded {
            loadMessages()
        }
    }

    @SuppressLint("UnsafeIntentLaunch")
    override fun onResume() {
        super.onResume()

        if (shouldExitSecureModeOnResume) {
            shouldExitSecureModeOnResume = false
            if (config.selectedConversationPin > 0) {
                closeSecureBox()
            }
        }

        if (config.needRestart || storedBackgroundColor != getProperBackgroundColor()) {
            finish()
            startActivity(intent)
            return
        }

        updateMenuColors()
        refreshMenuItemsAndTitle()

        getOrCreateConversationsAdapter().apply {
            if (storedPrimaryColor != getProperPrimaryColor()) {
                updatePrimaryColor()
            }

            if (storedTextColor != getProperTextColor()) {
                updateTextColor(getProperTextColor())
            }

            if (storedBackgroundColor != getProperBackgroundColor()) {
                updateBackgroundColor(getProperBackgroundColor())
            }

            if (storedFontSize != config.fontSize) {
                updateFontSize()
            }

            updateDrafts()
        }

        updateTextColors(binding.mainCoordinator)
        // Use same background logic as Contacts: surface color only for dynamic theme + light mode, else proper background
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.searchHolder.setBackgroundColor(backgroundColor)
        binding.conversationsFastscroller.setBackgroundColor(backgroundColor)
        binding.mainHolder.setBackgroundColor(backgroundColor)
        binding.conversationsList.setBackgroundColor(backgroundColor)
        binding.searchResultsList.setBackgroundColor(backgroundColor)

        val properPrimaryColor = getProperPrimaryColor()
        binding.noConversationsPlaceholder2.setTextColor(properPrimaryColor)
        binding.noConversationsPlaceholder2.underlineText()
        binding.conversationsFastscroller.updateColors(getProperAccentColor())
        binding.conversationsProgressBar.setIndicatorColor(properPrimaryColor)
        binding.conversationsProgressBar.trackColor = properPrimaryColor.adjustAlpha(LOWER_ALPHA)
        checkShortcut()

        binding.conversationsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                hideKeyboard()
            }
        })

        binding.searchResultsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                hideKeyboard()
            }
        })
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (config.selectedConversationPin > 0 && !isLaunchingSecretBox) {
            shouldExitSecureModeOnResume = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        config.needRestart = false
        bus?.unregister(this)
    }

    override fun onBackPressedCompat(): Boolean {
        val customToolbar = binding.mainMenu.toolbar ?: return appLockManager.lock().let { false }
        return if (customToolbar.isSearchExpanded) {
            customToolbar.collapseSearch()
            isSearchOpen = false
            true
        } else {
            appLockManager.lock()
            false
        }
    }

    private fun setupOptionsMenu() {
        binding.apply {
            mainMenu.toolbar?.inflateMenu(R.menu.menu_main)

            mainMenu.setOnSearchStateListener(object : BlurAppBarLayout.OnSearchStateListener {
                override fun onState(state: Int) {
                    when (state) {
                        MSearchView.SEARCH_START -> isSearchOpen = true
                        MSearchView.SEARCH_END -> {
                            fadeOutSearch()
                            isSearchOpen = false
                        }
                    }
                }

                override fun onSearchTextChanged(s: String?) {
                    val text = s ?: ""
                    searchQuery = text
                    if (text.isNotEmpty()) {
                        if (searchHolder.alpha < 1f) {
                            searchHolder.fadeIn()
                        }
                    } else {
                        fadeOutSearch()
                    }
                    searchTextChanged(text)
                    if (isSearchAlwaysShow) mainMenu.clearSearch()
                }
            })

            mainMenu.toolbar?.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.search -> {
                        if (mainMenu.toolbar?.isSearchExpanded != true) {
                            mainMenu.startSearch()
                            isSearchOpen = true
                        }
                        return@setOnMenuItemClickListener true
                    }
                    R.id.select_conversations -> {
                        if (mainMenu.toolbar?.isSearchExpanded == true) {
                            mainMenu.toolbar?.collapseSearch()
                            isSearchOpen = false
                        }
                        mainMenu.setExpanded(true)
                        getOrCreateConversationsAdapter().startActMode()
                    }
                    R.id.show_recycle_bin -> launchRecycleBin()
                    R.id.show_archived -> launchArchivedConversations()
                    R.id.show_blocked_numbers -> showBlockedNumbers()
                    R.id.unlock_protected_contacts -> {
                        if (config.selectedConversationPin > 0) {
                            closeSecureBox()
                        } else {
                            launchSecretBoxForUnlock()
                        }
                    }
                    R.id.settings -> launchSettings()
                    R.id.about -> launchAbout()
                    else -> return@setOnMenuItemClickListener false
                }
                return@setOnMenuItemClickListener true
            }

            mainMenu.clearSearch()
        }
    }

    private fun setupSearch(menu: Menu) {
        updateMenuItemColors(menu)
        val searchManager = getSystemService(SEARCH_SERVICE) as SearchManager
        mSearchMenuItem = menu.findItem(R.id.search)
        mSearchView = (mSearchMenuItem!!.actionView as SearchView).apply {
            val textColor = getProperTextColor()
            findViewById<TextView>(androidx.appcompat.R.id.search_src_text).apply {
                setTextColor(textColor)
                setHintTextColor(textColor)
                // Reduce left padding to a small value
                val smallPadding = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.small_margin)
                setPadding(smallPadding, paddingTop, paddingRight, paddingBottom)
            }
            findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn).apply {
                setImageResource(com.goodwy.commons.R.drawable.ic_clear_round)
                setColorFilter(textColor)
            }
            findViewById<View>(androidx.appcompat.R.id.search_plate)?.apply { // search underline
                background.setColorFilter(Color.TRANSPARENT, PorterDuff.Mode.MULTIPLY)
                // Reduce left padding on the search plate to a small value
                val smallPadding = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.small_margin)
                setPadding(smallPadding, paddingTop, paddingRight, paddingBottom)
            }
            setIconifiedByDefault(false)
            findViewById<ImageView>(androidx.appcompat.R.id.search_mag_icon).apply {
                setColorFilter(textColor)
            }

            setSearchableInfo(searchManager.getSearchableInfo(componentName))
            isSubmitButtonEnabled = false
            queryHint = getString(R.string.search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String) = false

                override fun onQueryTextChange(newText: String): Boolean {
                    if (isSearchOpen) {
                        searchQuery = newText
                        if (newText.isNotEmpty()) {
                            if (binding.searchHolder.alpha < 1f) {
                                binding.searchHolder.fadeIn()
                            }
                        } else {
                            fadeOutSearch()
                        }
                        searchTextChanged(newText)
                    }
                    return true
                }
            })
        }

        @Suppress("DEPRECATION")
        MenuItemCompat.setOnActionExpandListener(mSearchMenuItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
                isSearchOpen = true

                // Animate search bar appearance with smooth translation (slide in from right)
                mSearchView?.let { searchView ->
                    searchView.post {
                        // Get the parent toolbar width for smooth slide-in
                        val toolbar = binding.mainMenu.toolbar ?: return@post
                        val slideDistance = toolbar.width.toFloat()

                        // Start from right side
                        searchView.translationX = slideDistance
                        searchView.alpha = 0f

                        // Animate to center with smooth deceleration
                        searchView.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(350)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                            .start()
                    }
                }

                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
                if (isSearchOpen) {
                    fadeOutSearch()
                }

                isSearchOpen = false

                // Animate search bar disappearance with smooth translation (slide out to right)
                mSearchView?.let { searchView ->
                    val toolbar = binding.mainMenu.toolbar ?: return@let
                    val slideDistance = toolbar.width.toFloat()

                    searchView.animate()
                        .translationX(slideDistance)
                        .alpha(0f)
                        .setDuration(300)
                        .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                        .withEndAction {
                            searchView.translationX = 0f
                            searchView.alpha = 1f
                        }
                        .start()
                } ?: run {
                    // binding.mainDialpadButton.beVisible()
                }

                return true
            }
        })
    }

    private fun refreshMenuItemsAndTitle() {
        binding.mainMenu.toolbar?.menu?.apply {
            findItem(R.id.show_recycle_bin)?.isVisible = config.useRecycleBin
            findItem(R.id.show_archived)?.isVisible = config.isArchiveAvailable
            findItem(R.id.about)?.isVisible = false
            findItem(R.id.unlock_protected_contacts)?.title =
                if (config.selectedConversationPin > 0) getString(R.string.close_secure_box)
                else getString(R.string.secure_box)
            findItem(R.id.show_blocked_numbers)?.title =
                if (config.showBlockedNumbers) getString(com.goodwy.strings.R.string.hide_blocked_numbers)
                else getString(com.goodwy.strings.R.string.show_blocked_numbers)
        }
        binding.mainMenu.setTitle(
            if (config.selectedConversationPin > 0) getString(R.string.secure_box)
            else getString(R.string.messages)
        )
    }

    private fun showBlockedNumbers() {
        config.showBlockedNumbers = !config.showBlockedNumbers
        binding.mainMenu.toolbar?.menu?.findItem(R.id.show_blocked_numbers)?.title =
            if (config.showBlockedNumbers) getString(com.goodwy.strings.R.string.hide_blocked_numbers)
            else getString(com.goodwy.strings.R.string.show_blocked_numbers)
//        runOnUiThread {
//            getRecentsFragment()?.refreshItems()
//        }
        initMessenger()
    }

    private fun getSecretNumberFromResult(data: Intent?): Int? {
        if (data == null) return null
        val asInt = data.getIntExtra(SECRET_NUMBER_EXTRA, INVALID_CIPHER)
        if (asInt > INVALID_CIPHER) return asInt
        val asString = data.getStringExtra(SECRET_NUMBER_EXTRA)
        return asString?.toIntOrNull()?.takeIf { it >= 0 }
    }

    private val startSecretBoxForUnlock = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isLaunchingSecretBox = false
        if (result.resultCode != RESULT_OK) {
            pendingThreadIdsToEncrypt = null
            return@registerForActivityResult
        }

        val cipher = getSecretNumberFromResult(result.data) ?: run {
            pendingThreadIdsToEncrypt = null
            return@registerForActivityResult
        }

        val pendingThreadIds = pendingThreadIdsToEncrypt
        pendingThreadIdsToEncrypt = null
        if (pendingThreadIds != null) {
            ensureBackgroundThread {
                updateConversationPins(pendingThreadIds, cipher)
                runOnUiThread {
                    getOrCreateConversationsAdapter().finishActMode()
                    com.android.mms.helpers.refreshConversations()
                }
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                if (!isFinishing && !isDestroyed) {
                    if (setConversationPinScope(cipher)) {
                        initMessenger()
                        refreshMenuItemsAndTitle()
                    } else {
                        toast(com.goodwy.commons.R.string.unknown_error_occurred)
                    }
                }
            }
        }
    }

    private fun closeSecureBox() {
        setConversationPinScope(0)
        initMessenger()
        refreshMenuItemsAndTitle()
    }

    private fun setupTwoFingerSwipeGesture() {
        twoFingerGestureDetector = TwoFingerSlideGestureDetector(this,
            object : TwoFingerSlideGestureDetector.OnTwoFingerSlideGestureListener {
                override fun onTwoFingerSlide(
                    firstFingerX: Float,
                    firstFingerY: Float,
                    secondFingerX: Float,
                    secondFingerY: Float,
                    avgDeltaX: Float,
                    avgDeltaY: Float,
                    avgDistance: Float
                ) {
                    if (avgDeltaY > MIN_SWIPE_DISTANCE) {
                        launchSecretBoxForUnlock()
                    }
                }
            }
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { twoFingerGestureDetector.onTouchEvent(it) }
        return super.dispatchTouchEvent(ev)
    }

    private fun launchSecretBoxForUnlock() {
        try {
            isLaunchingSecretBox = true
            startSecretBoxForUnlock.launch(Intent(SECRET_BOX_PACKAGE))
        } catch (_: ActivityNotFoundException) {
            isLaunchingSecretBox = false
            toast(R.string.secret_box_app_not_found)
        }
    }

    fun requestEncryptConversations(threadIds: LongArray) {
        val validThreadIds = threadIds.filter { it > 0L }.distinct().toLongArray()
        if (validThreadIds.isEmpty()) return
        pendingThreadIdsToEncrypt = validThreadIds
        launchSecretBoxForUnlock()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == MAKE_DEFAULT_APP_REQUEST) {
            if (resultCode == RESULT_OK) {
                askPermissions()
            } else {
                finish()
            }
        } else if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultCode == RESULT_OK) {
            if (resultData != null) {
                val res: ArrayList<String> =
                    resultData.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) as ArrayList<String>

                val speechToText =  Objects.requireNonNull(res)[0]
                if (speechToText.isNotEmpty()) {
                    binding.mainMenu.setText(speechToText)
                }
            }
        }
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun initMVSideFrames() {
        binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
        binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
    }

    private fun initBouncy() {
        binding.mainMenu.post {
            // totalScrollRange is used by bouncy/offset logic if needed
        }
    }

    private fun initBouncyListener() {
        binding.mainMenu.setupOffsetListener { verticalOffset, height ->
            val h = if (height > 0) height else 1
            binding.mainMenu.titleView?.scaleX = (1 + 0.7f * verticalOffset / h)
            binding.mainMenu.titleView?.scaleY = (1 + 0.7f * verticalOffset / h)
        }
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val dp5 = (5 * resources.displayMetrics.density).toInt()
            binding.mVerticalSideFrameBottom.layoutParams =
                binding.mVerticalSideFrameBottom.layoutParams.apply { height = navHeight + dp5 }
            val bottomOffset = (20 * resources.displayMetrics.density).toInt()
            val fabLp = binding.conversationsFab.layoutParams as? ViewGroup.MarginLayoutParams
            if (fabLp != null) {
                // Don't add navHeight to margin: setupEdgeToEdge already pads barContainer bottom.
                // Use only a small offset so we don't double-apply insets (avoids huge gap in gesture nav).
                fabLp.bottomMargin = if (ime.bottom > 0) ime.bottom + bottomOffset else bottomOffset
                binding.conversationsFab.layoutParams = fabLp
            }
            insets
        }
    }

    private fun storeStateVariables() {
        storedPrimaryColor = getProperPrimaryColor()
        storedTextColor = getProperTextColor()
        storedBackgroundColor = getProperBackgroundColor()
        storedFontSize = config.fontSize
        config.needRestart = false
    }

    private fun updateMenuColors() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        val statusBarColor = if (config.changeColourTopBar) getRequiredStatusBarColor(useSurfaceColor) else backgroundColor
        binding.mainMenu.updateColors(statusBarColor, scrollingView?.computeVerticalScrollOffset() ?: 0)
        binding.mainMenu.toolbar?.updateSearchColors()
    }

    private fun loadMessages() {
        if (isQPlus()) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager!!.isRoleAvailable(RoleManager.ROLE_SMS)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    askPermissions()
                } else {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    startActivityForResult(intent, MAKE_DEFAULT_APP_REQUEST)
                }
            } else {
                toast(com.goodwy.commons.R.string.unknown_error_occurred)
                finish()
            }
        } else {
            if (Telephony.Sms.getDefaultSmsPackage(this) == packageName) {
                askPermissions()
            } else {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                startActivityForResult(intent, MAKE_DEFAULT_APP_REQUEST)
            }
        }
    }

    // while SEND_SMS and READ_SMS permissions are mandatory, READ_CONTACTS is optional.
    // If we don't have it, we just won't be able to show the contact name in some cases
    private fun askPermissions() {
        handlePermission(PERMISSION_READ_SMS) {
            if (it) {
                handlePermission(PERMISSION_SEND_SMS) {
                    if (it) {
                        handlePermission(PERMISSION_READ_CONTACTS) {
                            handleNotificationPermission { granted ->
                                if (!granted) {
                                    val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                                        ?: throw IllegalStateException("mainBlurTarget not found")
                                    PermissionRequiredDialog(
                                        activity = this,
                                        textId = com.goodwy.commons.R.string.allow_notifications_incoming_messages,
                                        blurTarget = blurTarget,
                                        positiveActionCallback = { openNotificationSettings() })
                                }
                            }

                            initMessenger()
                            bus = EventBus.getDefault()
                            try {
                                bus!!.register(this)
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        finish()
                    }
                }
            } else {
                finish()
            }
        }
    }

    private fun initMessenger() {
        getCachedConversations()
        binding.noConversationsPlaceholder2.setOnClickListener {
            launchNewConversation()
        }

        binding.conversationsFab.setOnClickListener {
            launchNewConversation()
        }
    }

    private fun getCachedConversations() {
        ensureBackgroundThread {
            // PIN-scoped mode should not render PIN=0 cached conversations first.
            val shouldUseCached = config.selectedConversationPin == 0
            val conversations = if (shouldUseCached) {
                try {
                    conversationsDB.getNonArchived().toMutableList() as ArrayList<Conversation>
                } catch (_: Exception) {
                    ArrayList()
                }
            } else {
                ArrayList()
            }

            val archived = if (shouldUseCached) {
                try {
                    conversationsDB.getAllArchived()
                } catch (_: Exception) {
                    listOf()
                }
            } else {
                listOf()
            }

            if (shouldUseCached) {
                // Load message counts for cached conversations
                conversations.forEach { conversation ->
                    try {
                        conversation.messageCount = messagesDB.getThreadMessageCount(conversation.threadId)
                    } catch (_: Exception) {
                        conversation.messageCount = 0
                    }
                }
            }

            runOnUiThread {
                setupConversations(conversations, cached = true)
                getNewConversations((conversations + archived).toMutableList() as ArrayList<Conversation>)
            }
            if (shouldUseCached) {
                conversations.forEach {
                    clearExpiredScheduledMessages(it.threadId)
                }
            }
        }
    }

    private fun getNewConversations(cachedConversations: ArrayList<Conversation>) {
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ensureBackgroundThread {
            val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            val conversations = getConversations(privateContacts = privateContacts)
            val isSecureMode = config.selectedConversationPin > 0

            if (isSecureMode) {
                // In secure mode, show only provider-filtered conversations for selected PIN.
                conversations.forEach { conversation ->
                    try {
                        conversation.messageCount = messagesDB.getThreadMessageCount(conversation.threadId)
                    } catch (_: Exception) {
                        conversation.messageCount = 0
                    }
                }
                runOnUiThread {
                    setupConversations(conversations)
                }
                return@ensureBackgroundThread
            }

            conversations.forEach { clonedConversation ->
                val threadIds = cachedConversations.map { it.threadId }
                if (!threadIds.contains(clonedConversation.threadId)) {
                    // If this is a new conversation and title equals phone number (not in contacts),
                    // remove country code from the display
                    val normalizedTitle = clonedConversation.title.normalizePhoneNumber()
                    val normalizedPhoneNumber = clonedConversation.phoneNumber.normalizePhoneNumber()
                    val updatedConversation = if (normalizedTitle == normalizedPhoneNumber || clonedConversation.title == clonedConversation.phoneNumber) {
                        val phoneNumberWithoutCountryCode = getDisplayNumberWithoutCountryCode(clonedConversation.phoneNumber)
                        clonedConversation.copy(title = phoneNumberWithoutCountryCode)
                    } else {
                        clonedConversation
                    }
                    conversationsDB.insertOrUpdate(updatedConversation)
                    cachedConversations.add(updatedConversation)
                }
            }

            cachedConversations.forEach { cachedConversation ->
                val threadId = cachedConversation.threadId

                val isTemporaryThread = cachedConversation.isScheduled
                val isConversationDeleted = !conversations.map { it.threadId }.contains(threadId)
                if (isConversationDeleted && !isTemporaryThread) {
                    conversationsDB.deleteThreadId(threadId)
                }

                val newConversation =
                    conversations.find { it.phoneNumber == cachedConversation.phoneNumber }
                if (isTemporaryThread && newConversation != null) {
                    // delete the original temporary thread and move any scheduled messages
                    // to the new thread
                    conversationsDB.deleteThreadId(threadId)
                    messagesDB.getScheduledThreadMessages(threadId)
                        .forEach { message ->
                            messagesDB.insertOrUpdate(
                                message.copy(threadId = newConversation.threadId)
                            )
                        }
                    insertOrUpdateConversation(newConversation, cachedConversation)
                }
            }

            cachedConversations.forEach { cachedConv ->
                val conv = conversations.find {
                    it.threadId == cachedConv.threadId && !Conversation.areContentsTheSame(
                        old = cachedConv, new = it
                    )
                }
                if (conv != null) {
                    // FIXME: Scheduled message date is being reset here. Conversations with
                    //  scheduled messages will have their original date.
                    insertOrUpdateConversation(conv)
                }
            }

            val allConversations = conversationsDB.getNonArchived() as ArrayList<Conversation>
            // Load message counts for all conversations
            allConversations.forEach { conversation ->
                try {
                    conversation.messageCount = messagesDB.getThreadMessageCount(conversation.threadId)
                } catch (_: Exception) {
                    conversation.messageCount = 0
                }
            }
            runOnUiThread {
                setupConversations(allConversations)
            }

            if (config.appRunCount == 1) {
                conversations.map { it.threadId }.forEach { threadId ->
                    val messages = getMessages(threadId, includeScheduledMessages = false)
                    messages.chunked(30).forEach { currentMessages ->
                        messagesDB.insertMessages(*currentMessages.toTypedArray())
                    }
                }
            }
        }
    }

    private fun getOrCreateConversationsAdapter(): ConversationsAdapter {
        // Match Contacts: same background for list as activity (getProperBackgroundColor when !useSurfaceColor)
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.conversationsList.setBackgroundColor(backgroundColor)

        var currAdapter = binding.conversationsList.adapter
        if (currAdapter == null) {
            hideKeyboard()
            currAdapter = ConversationsAdapter(
                activity = this,
                recyclerView = binding.conversationsList,
                onRefresh = { notifyDatasetChanged() },
                itemClick = { handleConversationClick(it) }
            )

            binding.conversationsList.adapter = currAdapter
            if (areSystemAnimationsEnabled) {
                binding.conversationsList.scheduleLayoutAnimation()
            }
        }
        return currAdapter as ConversationsAdapter
    }

    private fun setupConversations(
        conversations: ArrayList<Conversation>,
        cached: Boolean = false,
    ) {
        val sortedConversations = if (config.unreadAtTop) {
            conversations.sortedWith(
                compareByDescending<Conversation> {
                    config.pinnedConversations.contains(it.threadId.toString())
                }
                    .thenBy { it.read }
                    .thenByDescending { it.date }
            ).toMutableList() as ArrayList<Conversation>
        } else {
            conversations.sortedWith(
                compareByDescending<Conversation> {
                    config.pinnedConversations.contains(it.threadId.toString())
                }
                    .thenByDescending { it.date }
                    .thenByDescending { it.isGroupConversation } // Group chats at the top
            ).toMutableList() as ArrayList<Conversation>
        }

        if (cached && config.appRunCount == 1) {
            // there are no cached conversations on the first run so we show the
            // loading placeholder and progress until we are done loading from telephony
            showOrHideProgress(conversations.isEmpty())
        } else {
            showOrHideProgress(false)
            showOrHidePlaceholder(conversations.isEmpty())
        }

        try {
            getOrCreateConversationsAdapter().apply {
                updateConversations(sortedConversations) {
                    if (!cached) {
                        showOrHidePlaceholder(currentList.isEmpty())
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun showOrHideProgress(show: Boolean) {
        if (show) {
            binding.conversationsProgressBar.show()
            binding.noConversationsPlaceholder.beVisible()
            binding.noConversationsPlaceholder.text = getString(R.string.loading_messages)
        } else {
            binding.conversationsProgressBar.hide()
            binding.noConversationsPlaceholder.beGone()
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        binding.conversationsFastscroller.beGoneIf(show)
        binding.noConversationsPlaceholder.beVisibleIf(show)
        binding.noConversationsPlaceholder.text = getString(R.string.no_conversations_found)
        binding.noConversationsPlaceholder2.beVisibleIf(show)
    }

    private fun fadeOutSearch() {
        binding.searchHolder.animate()
            .alpha(0f)
            .setDuration(SHORT_ANIMATION_DURATION)
            .withEndAction {
                binding.searchHolder.beGone()
                searchTextChanged("", true)
            }.start()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun notifyDatasetChanged() {
        getOrCreateConversationsAdapter().notifyDataSetChanged()
    }

    private fun handleConversationClick(any: Any) {
        Intent(this, ThreadActivity::class.java).apply {
            val conversation = any as Conversation
            putExtra(THREAD_ID, conversation.threadId)
            putExtra(THREAD_TITLE, conversation.title)
            startActivity(this)
        }
    }

    private fun launchNewConversation() {
        hideKeyboard()
        Intent(this, NewConversationActivity::class.java).apply {
            startActivity(this)
        }
    }

    private fun checkShortcut() {
        val iconColor = getProperPrimaryColor()
        if (config.lastHandledShortcutColor != iconColor) {
            val newConversation = getCreateNewContactShortcut(iconColor)

            val manager = getSystemService(ShortcutManager::class.java)
            try {
                manager.dynamicShortcuts = listOf(newConversation)
                config.lastHandledShortcutColor = iconColor
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getCreateNewContactShortcut(iconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.new_conversation)
        val drawable =
            AppCompatResources.getDrawable(this, R.drawable.shortcut_plus)

        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_plus_background)
            .applyColorFilter(iconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, NewConversationActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "new_conversation")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .setRank(0)
            .build()
    }

    private fun searchTextChanged(text: String, forceUpdate: Boolean = false) {
        val customToolbar = binding.mainMenu.toolbar
        if (isSearchAlwaysShow && customToolbar?.isSearchExpanded != true && !forceUpdate) {
            return
        }

        lastSearchedText = text
        binding.searchPlaceholder2.beGoneIf(text.length >= 2)

        if (text.length >= 2) {
            ensureBackgroundThread {
                val searchQuery = "%$text%"
                val messages = messagesDB.getMessagesWithText(searchQuery)
                val conversations = conversationsDB.getConversationsWithText(searchQuery)
                // Load message counts for search results
                conversations.forEach { conversation ->
                    try {
                        conversation.messageCount = messagesDB.getThreadMessageCount(conversation.threadId)
                    } catch (_: Exception) {
                        conversation.messageCount = 0
                    }
                }
                if (text == lastSearchedText) {
                    showSearchResults(messages, conversations, text)
                }
            }
        } else {
            binding.searchPlaceholder.beVisible()
            binding.searchResultsList.beGone()
        }
        if (isSearchAlwaysShow)
            binding.mainMenu.clearSearch()
    }

    private fun showSearchResults(
        messages: List<Message>,
        conversations: List<Conversation>,
        searchedText: String,
    ) {
        val searchResults = ArrayList<SearchResult>()
        conversations.forEach { conversation ->
            val date = (conversation.date * 1000L).formatDateOrTime(
                context = this,
                hideTimeOnOtherDays = true,
                showCurrentYear = true
            )

            val searchResult = SearchResult(
                messageId = -1,
                title = conversation.title,
                phoneNumber = conversation.phoneNumber,
                snippet = conversation.phoneNumber,
                date = date,
                threadId = conversation.threadId,
                photoUri = conversation.photoUri,
                isCompany = conversation.isCompany,
                isBlocked = conversation.isBlocked
            )
            searchResults.add(searchResult)
        }

        messages.sortedByDescending { it.id }.forEach { message ->
            var recipient = message.senderName
            if (recipient.isEmpty() && message.participants.isNotEmpty()) {
                val participantNames = message.participants.map { it.name }
                recipient = TextUtils.join(", ", participantNames)
            }

            val phoneNumber = message.participants.firstOrNull()!!.phoneNumbers.firstOrNull()!!.normalizedNumber
            val date = (message.date * 1000L).formatDateOrTime(
                context = this,
                hideTimeOnOtherDays = true,
                showCurrentYear = true
            )
            val isCompany =
                if (message.participants.size == 1) message.participants.first().isABusinessContact() else false

            val searchResult = SearchResult(
                messageId = message.id,
                title = recipient,
                phoneNumber = phoneNumber,
                snippet = message.body,
                date = date,
                threadId = message.threadId,
                photoUri = message.senderPhotoUri,
                isCompany = isCompany
            )
            searchResults.add(searchResult)
        }

        runOnUiThread {
            binding.searchResultsList.beVisibleIf(searchResults.isNotEmpty())
            binding.searchPlaceholder.beVisibleIf(searchResults.isEmpty())

            val currAdapter = binding.searchResultsList.adapter
            if (currAdapter == null) {
                SearchResultsAdapter(this, searchResults, binding.searchResultsList, searchedText) {
                hideKeyboard()
                    Intent(this, ThreadActivity::class.java).apply {
                        putExtra(THREAD_ID, (it as SearchResult).threadId)
                        putExtra(THREAD_TITLE, it.title)
                        putExtra(SEARCHED_MESSAGE_ID, it.messageId)
                        startActivity(this)
                    }
                }.apply {
                    binding.searchResultsList.adapter = this
                }
            } else {
                (currAdapter as SearchResultsAdapter).updateItems(searchResults, searchedText)
            }
        }
    }

    private fun launchRecycleBin() {
        hideKeyboard()
        startActivity(Intent(applicationContext, RecycleBinConversationsActivity::class.java))
    }

    private fun launchArchivedConversations() {
        hideKeyboard()
        startActivity(Intent(applicationContext, ArchivedConversationsActivity::class.java))
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshConversations(@Suppress("unused") event: Events.RefreshConversations) {
        initMessenger()
    }

    private fun checkWhatsNewDialog() {
        whatsNewList().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
