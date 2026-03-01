package com.android.mms.activities

import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED
import android.provider.Telephony.Sms.STATUS_NONE
import android.speech.RecognizerIntent
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionInfo
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.DateUtils.FORMAT_NO_YEAR
import android.text.format.DateUtils.FORMAT_SHOW_DATE
import android.text.format.DateUtils.FORMAT_SHOW_TIME
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.view.MenuItemCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.goodwy.commons.dialogs.PermissionRequiredDialog
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.dialogs.RadioGroupIconDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.RadioItem
import com.goodwy.commons.models.SimpleContact
import com.android.mms.BuildConfig
import com.android.mms.R
import com.android.mms.adapters.AttachmentsAdapter
import com.android.mms.adapters.ThreadAdapter
import com.android.mms.helpers.MessageHolderHelper
import com.android.mms.databinding.ActivityThreadBinding
import com.android.mms.dialogs.InvalidNumberDialog
import com.android.mms.dialogs.RenameConversationDialog
import com.android.mms.dialogs.ScheduleMessageDialog
import com.android.mms.extensions.*
import com.android.common.view.MVSideFrame
import com.android.mms.helpers.*
import com.android.mms.messaging.*
import com.android.mms.models.*
import com.android.mms.models.ThreadItem.ThreadDateTime
import com.android.mms.models.ThreadItem.ThreadError
import com.android.mms.models.ThreadItem.ThreadSending
import com.android.mms.models.ThreadItem.ThreadSent
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.joda.time.DateTime
import douglasspgyn.com.github.circularcountdown.CircularCountdown
import douglasspgyn.com.github.circularcountdown.listener.CircularListener
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.collections.set

class ThreadActivity : SimpleActivity() {
    private val debugTag = "ThreadActivityFee"
    private var threadId = 0L
    private var currentSIMCardIndex = 0
    private var isActivityVisible = false
    private var refreshedSinceSent = false
    private var threadItems = ArrayList<ThreadItem>()
    private var bus: EventBus? = null
    private var conversation: Conversation? = null
    private var participants = ArrayList<SimpleContact>()
    private var privateContacts = ArrayList<SimpleContact>()
    private var messages = ArrayList<Message>()
    private val availableSIMCards = ArrayList<SIMCard>()
    private var pendingAttachmentsToSave: List<Attachment>? = null
    private var capturedImageUri: Uri? = null
    private var loadingOlderMessages = false
    private var allMessagesFetched = false
    private var isJumpingToMessage = false
    private var isRecycleBin = false
    private var isLaunchedFromShortcut = false

    private var isScheduledMessage: Boolean = false
    private var messageToResend: Long? = null
    private var scheduledMessage: Message? = null
    private lateinit var scheduledDateTime: DateTime

    private var isAttachmentPickerVisible = false
    private var wasKeyboardVisible = false
    private var isSpeechToTextAvailable = false
    private var expandedMessageFragment: com.android.mms.fragments.ExpandedMessageFragment? = null
    private var messageHolderHelper: MessageHolderHelper? = null
    private var isFeeInfoReceiverRegistered = false
    private val feeInfoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_FEE_INFO_SET) {
                updateAvailableMessageCountForCurrentSim()
            }
        }
    }

    private val binding by viewBinding(ActivityThreadBinding::inflate)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finish()
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initTheme()
        initMVSideFrames()
        initThreadAppBar()
        setupOptionsMenu()
        refreshMenuItems()

        val bottomView = if (isRecycleBin) binding.threadMessagesList else binding.messageHolder.root
        makeSystemBarsToTransparent()
        setupEdgeToEdge(
            padTopSystem = listOf(binding.topDetailsCompact.root),
            padBottomImeAndSystem = listOf(
                bottomView,
                binding.shortCodeHolder.root
            ),
            animateIme= true
        )
        setupMessagingEdgeToEdge()
//        setupMaterialScrollListener(null, binding.threadAppbar)

        val extras = intent.extras
        if (extras == null) {
            toast(com.goodwy.commons.R.string.unknown_error_occurred)
            finish()
            return
        }

        isSpeechToTextAvailable = if (config.useSpeechToText) isSpeechToTextAvailable() else false

        threadId = intent.getLongExtra(THREAD_ID, 0L)
//        intent.getStringExtra(THREAD_TITLE)?.let {
//            binding.threadToolbar.title = it
//        }
        isRecycleBin = intent.getBooleanExtra(IS_RECYCLE_BIN, false)
        isLaunchedFromShortcut = intent.getBooleanExtra(IS_LAUNCHED_FROM_SHORTCUT, false)

        bus = EventBus.getDefault()
        bus!!.register(this)

        ensureDefaultBubbleType()
        loadConversation()
        maybeSetupRecycleBinView()
    }

    override fun onResume() {
        super.onResume()
        if (isDarkTheme()) {
            @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                )
        }
        if (config.threadTopStyle == THREAD_TOP_LARGE) binding.topDetailsCompact.root.beGone()
        else binding.topDetailsLarge.beGone()

        val topBarColor = getColoredMaterialStatusBarColor()
        // Use updateTopBarColors for AppBarLayout + CustomToolbar (not MyAppBarLayout)
        updateTopBarColors(
            appBarView = binding.threadAppbar,
            colorBackground = topBarColor,
            customToolbar = binding.threadToolbar,
            setAppBarViewBackground = false
        )
        // Zero elevation for app bar
        val stateListAnimator = StateListAnimator()
        stateListAnimator.addState(
            IntArray(0),
            ObjectAnimator.ofFloat(binding.threadAppbar, "elevation", 0.0f)
        )
        binding.threadAppbar.stateListAnimator = stateListAnimator
        // Setup CustomToolbar navigation icon and colors
        val toolbar = binding.threadToolbar
        val contrastColor = topBarColor.getContrastColor()
        val itemColor = if (baseConfig.topAppBarColorIcon) getProperPrimaryColor() else contrastColor
        setupThreadToolbarNavigation(color = itemColor)
        // Update menu button color
        val overflowIconRes = getOverflowIcon(baseConfig.overflowIcon)
        toolbar.overflowIcon = resources.getColoredDrawableWithColor(this, overflowIconRes, itemColor)
        // Update menu item icon colors (including action buttons like dial_number)
        updateMenuItemIconColors()

        isActivityVisible = true

        notificationManager.cancel(threadId.hashCode())

        ensureBackgroundThread {
            val newConv = conversationsDB.getConversationWithThreadId(threadId)
            if (newConv != null) {
                conversation = newConv
                runOnUiThread {
                    setupThreadTitle()
                }
            }

            val smsDraft = getSmsDraft(threadId)
            if (smsDraft.isNotEmpty()) {
                runOnUiThread {
                    binding.messageHolder.threadTypeMessage.setText(smsDraft)
                    binding.messageHolder.threadTypeMessage.setSelection(smsDraft.length)
                    binding.messageHolder.threadCharacterCounter.beVisibleIf(config.showCharacterCounter)
                }
            }

            markThreadMessagesRead(threadId)
        }

//        val bottomBarColor = getBottomBarColor()
//        binding.shortCodeHolder.root.setBackgroundColor(bottomBarColor)
//        binding.messageHolder.attachmentPickerHolder.setBackgroundColor(bottomBarColor)
    }

    override fun onStart() {
        super.onStart()
        registerFeeInfoReceiverIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        saveDraftMessage()
        bus?.post(Events.RefreshConversations())
        isActivityVisible = false
    }

    override fun onStop() {
        super.onStop()
        unregisterFeeInfoReceiverIfNeeded()
        saveDraftMessage()
    }

//    override fun onBackPressedCompat(): Boolean {
//        isAttachmentPickerVisible = false
//        return if (binding.messageHolder.attachmentPickerHolder.isVisible()) {
//            hideAttachmentPicker()
//            true
//        } else {
//            false
//        }
//    }

    override fun onDestroy() {
        unregisterFeeInfoReceiverIfNeeded()
        super.onDestroy()
        bus?.unregister(this)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerFeeInfoReceiverIfNeeded() {
        if (isFeeInfoReceiverRegistered) return
        runCatching {
            registerReceiver(feeInfoReceiver, IntentFilter(ACTION_FEE_INFO_SET))
            isFeeInfoReceiverRegistered = true
        }
    }

    private fun unregisterFeeInfoReceiverIfNeeded() {
        if (!isFeeInfoReceiverRegistered) return
        runCatching {
            unregisterReceiver(feeInfoReceiver)
        }
        isFeeInfoReceiverRegistered = false
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun initMVSideFrames() {
        binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
        binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
    }

    /**
     * Applies app bar background and behavior like [com.goodwy.commons.views.BlurAppBarLayout]:
     * - Sets appBarBackground drawable programmatically (AppBarLayout may not apply it from XML).
     * - Zero elevation and disables lift-on-scroll so the bar stays consistent.
     */
    private fun initThreadAppBar() {
        val appBar = binding.threadAppbar
        // appBar.setBackgroundResource(com.android.common.R.drawable.bg_cmn_appbar_up)
        appBar.elevation = 0f
        ViewCompat.setElevation(appBar, 0f)
        appBar.stateListAnimator = null
        appBar.isLiftOnScroll = false
        appBar.isLifted = false
    }

    private fun isDarkTheme(): Boolean {
        return (getSystemService(UI_MODE_SERVICE) as UiModeManager).nightMode == UiModeManager.MODE_NIGHT_YES
    }

    private fun ensureDefaultBubbleType() {
        if (getBubbleDrawableOption(config.bubbleDrawableSet) == null) {
            config.bubbleDrawableSet = 1
        }
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val barContainer = binding.messageHolder.root
        val dp5 = (5 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            binding.mVerticalSideFrameBottom.layoutParams =
                binding.mVerticalSideFrameBottom.layoutParams.apply { height = navHeight + dp5 }

            if (barContainer != null) {
                val bottomBarLp = barContainer.layoutParams as ViewGroup.MarginLayoutParams
                val bottomOffset = dp(3).toInt()
                val appBarHeightPx = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.normal_app_bar_height)

                val messagesList = binding.threadMessagesList
                if (ime.bottom > 0) {
                    messagesList.setPadding(0, appBarHeightPx, 0, dp(50) + navHeight + ime.bottom)
                    messagesList.scrollToPosition((messagesList.adapter?.itemCount ?: 1) - 1)
                } else {
                    // Don't add navHeight to margin: setupEdgeToEdge already pads barContainer bottom.
                    // Use only a small offset so we don't double-apply insets (avoids huge gap in gesture nav).
                    bottomBarLp.bottomMargin = bottomOffset
                    messagesList.setPadding(0, appBarHeightPx + dp(10), 0, dp(90) + navHeight)
                    barContainer.layoutParams = bottomBarLp
                }
            }
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun saveDraftMessage() {
        val draftMessage = messageHolderHelper?.getMessageText() ?: ""
        ensureBackgroundThread {
            if (draftMessage.isNotEmpty() && (messageHolderHelper?.getAttachmentSelections()?.isEmpty() != false)) {
                saveSmsDraft(draftMessage, threadId)
            } else {
                deleteSmsDraft(threadId)
            }
        }
    }

    private fun refreshMenuItems() {
        val firstPhoneNumber = participants.firstOrNull()?.phoneNumbers?.firstOrNull()?.value
        val archiveAvailable = config.isArchiveAvailable
        binding.threadToolbar.menu.apply {
            findItem(R.id.delete)?.isVisible = threadItems.isNotEmpty()
            findItem(R.id.select_messages)?.isVisible = threadItems.isNotEmpty()
            findItem(R.id.restore)?.isVisible = threadItems.isNotEmpty() && isRecycleBin
            findItem(R.id.archive)?.isVisible =
                threadItems.isNotEmpty() && conversation?.isArchived == false && !isRecycleBin && archiveAvailable
            findItem(R.id.unarchive)?.isVisible =
                threadItems.isNotEmpty() && conversation?.isArchived == true && !isRecycleBin && archiveAvailable
            findItem(R.id.rename_conversation)?.isVisible = participants.size > 1 && conversation != null && !isRecycleBin
            findItem(R.id.conversation_details)?.isVisible = conversation != null && !isRecycleBin
            findItem(R.id.block_number)?.isVisible = !isRecycleBin
            findItem(R.id.dial_number)?.isVisible = participants.size == 1 && !isSpecialNumber() && !isRecycleBin
            findItem(R.id.mark_as_unread)?.isVisible = threadItems.isNotEmpty() && !isRecycleBin

            // allow saving number in cases when we don't have it stored yet and it is a casual readable number
            findItem(R.id.add_number_to_contact)?.isVisible =
                participants.size == 1 && participants.first().name == firstPhoneNumber && firstPhoneNumber.any {
                    it.isDigit()
                } && !isRecycleBin
            val unblockText = if (participants.size == 1) com.goodwy.strings.R.string.unblock_number else com.goodwy.strings.R.string.unblock_numbers
            val blockText = if (participants.size == 1) com.goodwy.commons.R.string.block_number else com.goodwy.commons.R.string.block_numbers
            findItem(R.id.block_number)?.title = if (isBlockNumbers()) getString(unblockText) else getString(blockText)
        }
        binding.threadToolbar.invalidateMenu()
        // Update menu item icon colors after refreshing menu items
        updateMenuItemIconColors()
    }
    
    private fun updateMenuItemIconColors() {
        val topBarColor = getColoredMaterialStatusBarColor()
        val contrastColor = topBarColor.getContrastColor()
        val itemColor = if (baseConfig.topAppBarColorIcon) getProperPrimaryColor() else contrastColor
        
        val toolbar = binding.threadToolbar
        val menu = toolbar.menu
        for (i in 0 until menu.size()) {
            try {
                menu.getItem(i)?.icon?.setTint(itemColor)
            } catch (_: Exception) {
            }
        }
    }

    private fun setupOptionsMenu() {
        // Explicitly inflate menu to ensure it's ready (XML inflation happens asynchronously)
        binding.threadToolbar.inflateMenu(R.menu.menu_thread)
        // Force dial_number to show as action (like MainActivity search item) so CustomToolbar shows it in the bar
        binding.threadToolbar.menu.findItem(R.id.dial_number)?.let { item ->
            MenuItemCompat.setShowAsAction(item, MenuItemCompat.SHOW_AS_ACTION_ALWAYS)
        }
        binding.threadToolbar.invalidateMenu()
        binding.threadToolbar.setOnMenuItemClickListener { menuItem ->
            if (participants.isEmpty()) {
                return@setOnMenuItemClickListener true
            }

            when (menuItem.itemId) {
                R.id.block_number -> blockNumber()
                R.id.delete -> askConfirmDelete()
                R.id.restore -> askConfirmRestoreAll()
                R.id.archive -> archiveConversation()
                R.id.unarchive -> unarchiveConversation()
                R.id.rename_conversation -> renameConversation()
                R.id.conversation_details -> launchConversationDetails(threadId)
                R.id.add_number_to_contact -> addNumberToContact()
                R.id.dial_number -> dialNumber()
                R.id.mark_as_unread -> markAsUnread()
                R.id.select_messages -> getOrCreateThreadAdapter().startActMode()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode != RESULT_OK) return
        messageToResend = null

        // Handle speech-to-text
        if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultData != null) {
            val res: ArrayList<String> =
                resultData.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) as ArrayList<String>
            val speechToText = Objects.requireNonNull(res)[0]
            val draft = messageHolderHelper?.getMessageText() ?: ""
            val draftPlusSpeech =
                if (draft.isNotEmpty()) {
                    if (draft.last().toString() != " ") "$draft $speechToText" else "$draft $speechToText"
                } else speechToText
            if (draftPlusSpeech.isNotEmpty()) {
                ensureBackgroundThread {
                    saveSmsDraft(draftPlusSpeech, threadId)
                }
                messageHolderHelper?.setMessageText(draftPlusSpeech)
            }
            return
        }

        // Handle attachments via helper
        messageHolderHelper?.handleActivityResult(requestCode, resultCode, resultData)
        
        // Handle contact attachment and save operations
        val data = resultData?.data
        if (data != null) {
            when (requestCode) {
                MessageHolderHelper.PICK_CONTACT_INTENT -> addContactAttachment(data)
                PICK_SAVE_FILE_INTENT -> saveAttachments(resultData)
                PICK_SAVE_DIR_INTENT -> saveAttachments(resultData)
            }
        }
    }

    private fun setupCachedMessages(callback: () -> Unit) {
        ensureBackgroundThread {
            messages = try {
                if (isRecycleBin) {
                    messagesDB.getThreadMessagesFromRecycleBin(threadId)
                } else {
                    if (config.useRecycleBin) {
                        messagesDB.getNonRecycledThreadMessages(threadId)
                    } else {
                        messagesDB.getThreadMessages(threadId)
                    }
                }.toMutableList() as ArrayList<Message>
            } catch (_: Exception) {
                ArrayList()
            }
            clearExpiredScheduledMessages(threadId, messages)
            messages.removeAll { it.isScheduled && it.millis() < System.currentTimeMillis() }

            messages.sortBy { it.date }
            if (messages.size > MESSAGES_LIMIT) {
                messages = ArrayList(messages.takeLast(MESSAGES_LIMIT))
            }

            setupParticipants()
            setupAdapter()

            runOnUiThread {
                if (messages.isEmpty() && !isSpecialNumber()) {
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                    binding.messageHolder.threadTypeMessage.requestFocus()
                }

                setupThreadTitle()
                setupSIMSelector()
                //updateMessageType()
                callback()
            }
        }
    }

    private fun setupThread(callback: () -> Unit) {
        if (conversation == null && isLaunchedFromShortcut) {
            if (isTaskRoot) {
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(this)
                }
            }
            finish()
            return
        }
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ensureBackgroundThread {
            privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)

            val cachedMessagesCode = messages.clone().hashCode()
            if (!isRecycleBin) {
                messages = getMessages(threadId)
                if (config.useRecycleBin) {
                    val recycledMessages = messagesDB.getThreadMessagesFromRecycleBin(threadId)
                    messages = messages.filterNotInByKey(recycledMessages) { it.getStableId() }
                }
            }

            val hasParticipantWithoutName = participants.any { contact ->
                contact.phoneNumbers.map { it.normalizedNumber }.contains(contact.name)
            }

            try {
                if (participants.isNotEmpty() && messages.hashCode() == cachedMessagesCode && !hasParticipantWithoutName) {
                    setupAdapter()
                    runOnUiThread { callback() }
                    return@ensureBackgroundThread
                }
            } catch (_: Exception) {
            }

            setupParticipants()

            // check if no participant came from a privately stored contact in Simple Contacts
            if (privateContacts.isNotEmpty()) {
                val senderNumbersToReplace = HashMap<String, String>()
                participants.filter { it.doesHavePhoneNumber(it.name) }.forEach { participant ->
                    privateContacts.firstOrNull { it.doesHavePhoneNumber(participant.phoneNumbers.first().normalizedNumber) }
                        ?.apply {
                            senderNumbersToReplace[participant.phoneNumbers.first().normalizedNumber] = name
                            participant.name = name
                            participant.photoUri = photoUri
                        }
                }

                messages.forEach { message ->
                    if (senderNumbersToReplace.keys.contains(message.senderName)) {
                        message.senderName = senderNumbersToReplace[message.senderName]!!
                    }
                }
            }

            if (participants.isEmpty()) {
                val name = intent.getStringExtra(THREAD_TITLE) ?: ""
                val number = intent.getStringExtra(THREAD_NUMBER)
                if (number == null) {
                    toast(com.goodwy.commons.R.string.unknown_error_occurred)
                    finish()
                    return@ensureBackgroundThread
                }

                val phoneNumber = PhoneNumber(number, 0, "", number)
                val contact = SimpleContact(
                    rawId = 0,
                    contactId = 0,
                    name = name,
                    photoUri = "",
                    phoneNumbers = arrayListOf(phoneNumber),
                    birthdays = ArrayList(),
                    anniversaries = ArrayList()
                )
                participants.add(contact)
            }

            if (!isRecycleBin) {
                messages.chunked(30).forEach { currentMessages ->
                    messagesDB.insertMessages(*currentMessages.toTypedArray())
                }
            }

            setupAdapter()
            runOnUiThread {
                setupThreadTitle()
                setupSIMSelector()
                callback()
            }
        }
        updateContactImage()
    }

    private fun getOrCreateThreadAdapter(): ThreadAdapter {
        if (isDynamicTheme() && !isSystemInDarkMode()) {
            binding.threadHolder.setBackgroundColor(getSurfaceColor())
        }
        var currAdapter = binding.threadMessagesList.adapter
        if (currAdapter == null) {
            currAdapter = ThreadAdapter(
                activity = this,
                recyclerView = binding.threadMessagesList,
                itemClick = { handleItemClick(it) },
                isRecycleBin = isRecycleBin,
                isGroupChat = participants.size > 1,
                deleteMessages = { messages, toRecycleBin, fromRecycleBin, isPopupMenu ->
                    deleteMessages(messages, toRecycleBin, fromRecycleBin, isPopupMenu)
                }
            )

            binding.threadMessagesList.adapter = currAdapter
        }
        return currAdapter as ThreadAdapter
    }

    private fun setupAdapter() {
        threadItems = getThreadItems()

        runOnUiThread {
            refreshMenuItems()
            getOrCreateThreadAdapter().apply {
                val layoutManager = binding.threadMessagesList.layoutManager as LinearLayoutManager
                val lastPosition = itemCount - 1
                val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
                val shouldScrollToBottom =
                    currentList.lastOrNull() != threadItems.lastOrNull() && lastPosition - lastVisiblePosition == 1
                updateMessages(threadItems, if (shouldScrollToBottom) lastPosition else -1)
            }
        }

    }

    private fun scrollToBottom() {
        val position = getOrCreateThreadAdapter().currentList.lastIndex
        if (position >= 0) {
            binding.threadMessagesList.smoothScrollToPosition(position)
        }
    }

    private fun setupScrollListener() {
        binding.threadMessagesList.onScroll(
            onScrolled = { dx, dy ->
                tryLoadMoreMessages()
                val layoutManager = binding.threadMessagesList.layoutManager as LinearLayoutManager
                val lastVisibleItemPosition = layoutManager.findLastCompletelyVisibleItemPosition()
                val isCloseToBottom =
                    lastVisibleItemPosition >= getOrCreateThreadAdapter().itemCount - SCROLL_TO_BOTTOM_FAB_LIMIT
                val fab = binding.scrollToBottomFab
                if (isCloseToBottom) fab.hide() else fab.show()
                // Update top bar (status bar + toolbar icon) colors on scroll so they match content behind transparent bar
                if (config.changeColourTopBar) {
                    val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
                    val scrollOffset = binding.threadMessagesList.computeVerticalScrollOffset()
                    val color = if (scrollOffset == 0) {
                        if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
                    } else {
                        getColoredMaterialStatusBarColor()
                    }
                    updateTopBarColors(
                        binding.threadAppbar,
                        color,
                        binding.threadToolbar,
                        setAppBarViewBackground = false
                    )
                    val contrastColor = color.getContrastColor()
                    val itemColor = if (baseConfig.topAppBarColorIcon) getProperPrimaryColor() else contrastColor
                    setupThreadToolbarNavigation(color = itemColor)
                }
            },
            onScrollStateChanged = { newState ->
                if (newState == RecyclerView.SCROLL_STATE_IDLE) tryLoadMoreMessages()
            }
        )
    }

    private fun setupThreadToolbarNavigation(color: Int) {
        val toolbar = binding.threadToolbar
        toolbar.navigationIcon = resources.getColoredDrawableWithColor(
            this,
            com.android.common.R.drawable.ic_cmn_arrow_left_fill,
            color
        )
        toolbar.setNavigationContentDescription(com.goodwy.commons.R.string.back)
        toolbar.setNavigationOnClickListener {
            hideKeyboard()
            if (!onBackPressedCompat()) {
                finish()
            }
        }
    }

    private fun handleItemClick(any: Any) {
        when {
            any is Message && any.isScheduled -> showScheduledMessageInfo(any)
            any is ThreadError -> {
                binding.messageHolder.threadTypeMessage.setText(any.messageText)
                messageToResend = any.messageId
            }
        }
    }

    private fun deleteMessages(
        messagesToRemove: List<Message>,
        toRecycleBin: Boolean,
        fromRecycleBin: Boolean,
        isPopupMenu: Boolean = false,
    ) {
        val deletePosition = threadItems.indexOf(messagesToRemove.first())
        messages.removeAll(messagesToRemove.toSet())
        threadItems = getThreadItems()

        runOnUiThread {
            if (messages.isEmpty() && !isPopupMenu) {
                finish()
            } else {
                getOrCreateThreadAdapter().apply {
                    updateMessages(threadItems, scrollPosition = deletePosition)
                    finishActMode()
                }
            }
        }

        messagesToRemove.forEach { message ->
            val messageId = message.id
            if (message.isScheduled) {
                deleteScheduledMessage(messageId)
                cancelScheduleSendPendingIntent(messageId)
            } else {
                if (toRecycleBin) {
                    moveMessageToRecycleBin(messageId)
                } else if (fromRecycleBin) {
                    restoreMessageFromRecycleBin(messageId)
                } else {
                    deleteMessage(messageId, message.isMMS)
                }
            }
        }
        updateLastConversationMessage(threadId)

        // move all scheduled messages to a temporary thread when there are no real messages left
        if (messages.isNotEmpty() && messages.all { it.isScheduled }) {
            val scheduledMessage = messages.last()
            val fakeThreadId = generateRandomId()
            createTemporaryThread(scheduledMessage, fakeThreadId, conversation)
            updateScheduledMessagesThreadId(messages, fakeThreadId)
            threadId = fakeThreadId
        }
    }

    private fun jumpToMessage(messageId: Long) {
        if (messages.any { it.id == messageId }) {
            val index = threadItems.indexOfFirst { (it as? Message)?.id == messageId }
            if (index != -1) binding.threadMessagesList.smoothScrollToPosition(index)
            return
        }

        ensureBackgroundThread {
            if (loadingOlderMessages) return@ensureBackgroundThread
            loadingOlderMessages = true
            isJumpingToMessage = true

            var cutoff = messages.firstOrNull()?.date ?: Int.MAX_VALUE
            var found = false
            var loops = 0

            // not the best solution, but this will do for now.
            while (!found && !allMessagesFetched) {
                if (fetchOlderMessages(cutoff).isEmpty() || loops >= 1000) break
                cutoff = messages.first().date
                found = messages.any { it.id == messageId }
                loops++
            }

            threadItems = getThreadItems()
            runOnUiThread {
                loadingOlderMessages = false
                val index = threadItems.indexOfFirst { (it as? Message)?.id == messageId }
                getOrCreateThreadAdapter().updateMessages(
                    newMessages = threadItems, scrollPosition = index, smoothScroll = true
                )
                isJumpingToMessage = false
            }
        }
    }

    private fun tryLoadMoreMessages() {
        if (isJumpingToMessage) return
        val layoutManager = binding.threadMessagesList.layoutManager as LinearLayoutManager
        if (layoutManager.findFirstVisibleItemPosition() <= PREFETCH_THRESHOLD) {
            loadMoreMessages()
        }
    }

    private fun loadMoreMessages() {
        if (messages.isEmpty() || allMessagesFetched || loadingOlderMessages) return
        loadingOlderMessages = true
        val cutoff = messages.first().date
        ensureBackgroundThread {
            fetchOlderMessages(cutoff)
            threadItems = getThreadItems()
            runOnUiThread {
                loadingOlderMessages = false
                getOrCreateThreadAdapter().updateMessages(threadItems)
                getOrCreateThreadAdapter().updateTitle()
            }
        }
    }

    private fun fetchOlderMessages(cutoff: Int): List<Message> {
        var older = getMessages(threadId, cutoff)
            .filterNotInByKey(messages) { it.getStableId() }
        if (config.useRecycleBin && !isRecycleBin) {
            val recycledMessages = messagesDB.getThreadMessagesFromRecycleBin(threadId)
            older = older.filterNotInByKey(recycledMessages) { it.getStableId() }
        }

        if (older.isEmpty()) {
            allMessagesFetched = true
            return older
        }

        messages.addAll(0, older)
        return older
    }

    private fun loadConversation() {
        handlePermission(PERMISSION_READ_PHONE_STATE) { granted ->
            if (granted) {
        setupMessageHolderHelper()
        setupButtons()
        setupConversation()
        setupCachedMessages {
                    setupThread {
                        val searchedMessageId = intent.getLongExtra(SEARCHED_MESSAGE_ID, -1L)
                        intent.removeExtra(SEARCHED_MESSAGE_ID)
                        if (searchedMessageId != -1L) {
                            jumpToMessage(searchedMessageId)
                        }
                    }
                    setupScrollListener()
                }
            } else {
                finish()
            }
        }
    }

    private fun setupConversation() {
        ensureBackgroundThread {
            conversation = conversationsDB.getConversationWithThreadId(threadId)
        }
    }

    private fun setupMessageHolderHelper() {
        isSpeechToTextAvailable = if (config.useSpeechToText) isSpeechToTextAvailable() else false
        
        messageHolderHelper = MessageHolderHelper(
            activity = this,
            binding = binding.messageHolder,
            onSendMessage = { text, subscriptionId, attachments ->
                sendMessageWithHelper(text, subscriptionId, attachments)
            },
            onSpeechToText = { speechToText() },
            onExpandMessage = { showExpandedMessageFragment() },
            onTextChanged = { 
                messageToResend = null
            }
        )
        
        messageHolderHelper?.setup(isSpeechToTextAvailable)
        
        binding.messageHolder.apply {
            threadTypeMessage.setText(intent.getStringExtra(THREAD_TEXT))
            threadAddAttachmentHolder.setOnClickListener {
                if (attachmentPickerHolder.isVisible()) {
                    isAttachmentPickerVisible = false
                    messageHolderHelper?.hideAttachmentPicker()
                } else {
                    isAttachmentPickerVisible = true
                    messageHolderHelper?.showAttachmentPicker()
                }
                threadTypeMessage.requestApplyInsets()
            }

            if (intent.extras?.containsKey(THREAD_ATTACHMENT_URI) == true) {
                val uri = intent.getStringExtra(THREAD_ATTACHMENT_URI)!!.toUri()
                messageHolderHelper?.addAttachment(uri)
            } else if (intent.extras?.containsKey(THREAD_ATTACHMENT_URIS) == true) {
                (intent.getSerializableExtra(THREAD_ATTACHMENT_URIS) as? ArrayList<Uri>)?.forEach {
                    messageHolderHelper?.addAttachment(it)
                }
            }
        }
        
        messageHolderHelper?.setupAttachmentPicker(
            onChoosePhoto = { launchGetContentIntent(arrayOf("image/*", "video/*"), MessageHolderHelper.PICK_PHOTO_INTENT) },
            onChooseVideo = { launchGetContentIntent(arrayOf("video/*"), MessageHolderHelper.PICK_VIDEO_INTENT) },
            onTakePhoto = { launchCapturePhotoIntent() },
            onRecordVideo = { launchCaptureVideoIntent() },
            onRecordAudio = { launchCaptureAudioIntent() },
            onPickFile = { launchGetContentIntent(arrayOf("*/*"), MessageHolderHelper.PICK_DOCUMENT_INTENT) },
            onPickContact = { launchPickContactIntent() },
            onScheduleMessage = { launchScheduleSendDialog() },
            onPickQuickText = {
                val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                com.android.mms.dialogs.QuickTextSelectionDialog(this, blurTarget) { selectedText ->
                    messageHolderHelper?.insertText(selectedText)
                }
            }
        )
        
        messageHolderHelper?.hideAttachmentPicker()
    }
    
    private fun setupButtons() = binding.apply {
        updateTextColors(threadHolder)
        val textColor = getProperTextColor()
        val properPrimaryColor = getProperPrimaryColor()
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val surfaceColor = if (useSurfaceColor) getProperBackgroundColor() else getSurfaceColor()

        binding.messageHolder.apply {
            threadMessagesFastscroller.updateColors(getProperAccentColor())
            threadAddAttachment.applyColorFilter(textColor)
            threadAddAttachment.background.applyColorFilter(surfaceColor)
        }
        
        scrollToBottomFab.setOnClickListener {
            scrollToBottom()
        }
        scrollToBottomFab.backgroundTintList = ColorStateList.valueOf(getBottomBarColor())
        scrollToBottomFab.applyColorFilter(textColor)

        setupScheduleSendUi()
    }
    
    private fun sendMessageWithHelper(text: String, subscriptionId: Int?, attachments: List<Attachment>) {
        val finalSubscriptionId = subscriptionId ?: availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
            ?: SmsManager.getDefaultSmsSubscriptionId()
        
        if (isScheduledMessage) {
            sendScheduledMessage(text, finalSubscriptionId)
        } else {
            sendNormalMessage(text, finalSubscriptionId)
        }
    }

    private fun askForExactAlarmPermissionIfNeeded(callback: () -> Unit = {}) {
        if (isSPlus()) {
            val alarmManager: AlarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            if (alarmManager.canScheduleExactAlarms()) {
                callback()
            } else {
                val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                PermissionRequiredDialog(
                    activity = this,
                    textId = com.goodwy.commons.R.string.allow_alarm_scheduled_messages,
                    blurTarget = blurTarget,
                    positiveActionCallback = {
                        openRequestExactAlarmSettings(BuildConfig.APPLICATION_ID)
                    },
                )
            }
        } else {
            callback()
        }
    }

    private fun setupParticipants() {
        if (participants.isEmpty()) {
            participants = if (messages.isEmpty()) {
                val intentNumbers = getPhoneNumbersFromIntent()
                val participants = getThreadParticipants(threadId, null)
                fixParticipantNumbers(participants, intentNumbers)
            } else {
                messages.first().participants
            }
            runOnUiThread {
                maybeDisableShortCodeReply()
            }
        }
    }

    private fun isSpecialNumber(): Boolean {
        val addresses = participants.getAddresses()
        return addresses.any { isShortCodeWithLetters(it) }
    }

    private fun maybeDisableShortCodeReply() {
        if (isSpecialNumber() && !isRecycleBin) {
            currentFocus?.clearFocus()
            hideKeyboard()
            binding.messageHolder.threadTypeMessage.text?.clear()
            binding.messageHolder.root.beGone()
            binding.shortCodeHolder.root.beVisible()
            
            val textColor = getProperTextColor()
            binding.shortCodeHolder.replyDisabledText.setTextColor(textColor)
            binding.shortCodeHolder.replyDisabledInfo.apply {
                applyColorFilter(textColor)
                setOnClickListener {
                    val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                        ?: throw IllegalStateException("mainBlurTarget not found")
                    InvalidNumberDialog(
                        activity = this@ThreadActivity,
                        text = getString(R.string.invalid_short_code_desc),
                        blurTarget = blurTarget
                    )
                }
                tooltipText = getString(com.goodwy.commons.R.string.more_info)
            }
        }
    }

    private fun setupThreadTitle() = binding.apply {
        val textColor = getProperTextColor()
        val title = conversation?.title
        // For multiple participants always show "first user's name or phone and N others" in sender_name_large/sender_name
        val threadTitle = if (participants.size > 1) {
            participants.getThreadTitle(this@ThreadActivity)
        } else {
            if (!title.isNullOrEmpty()) title else participants.getThreadTitle(this@ThreadActivity)
        }
        val threadSubtitle = participants.getThreadSubtitle(this@ThreadActivity)
        threadToolbar.title = ""
        when (config.threadTopStyle) {
            THREAD_TOP_COMPACT -> {
                topDetailsLarge.beGone()
                topDetailsCompact.root.beVisible()
                topDetailsCompact.apply {
                    senderPhoto.beVisibleIf(config.showContactThumbnails)
                    if (threadTitle.isNotEmpty()) {
                        senderName.text = threadTitle
                        senderName.setTextColor(textColor)
                    }
                    senderNumber.beGoneIf(threadSubtitle.isEmpty() || threadTitle == threadSubtitle || participants.size > 1)
                    senderNumber.text = threadSubtitle
                    senderNumber.setTextColor(textColor)
                    arrayOf(
                        senderPhoto,
                        senderName,
                        senderNumber
                    ).forEach {
                        it.setOnClickListener {
                            if (conversation != null) launchConversationDetails(threadId)
                        }
                    }
                    senderName.setOnLongClickListener { copyToClipboard(senderName.value); true }
                    senderNumber.setOnLongClickListener { copyToClipboard(senderNumber.value); true }
                }
            }
            THREAD_TOP_LARGE -> {
                topDetailsCompact.root.beGone()
                topDetailsLarge.beVisible()
                topDetailsLarge.apply {
                    // senderPhotoLarge.beVisibleIf(config.showContactThumbnails)
                    if (threadTitle.isNotEmpty()) {
                        senderNameLarge.text = threadTitle
                        senderNameLarge.setTextColor(textColor)
                    }
                    senderNumberLarge.beGoneIf(threadSubtitle.isEmpty() || threadTitle == threadSubtitle || participants.size > 1)
                    senderNumberLarge.text = threadSubtitle
                    senderNumberLarge.setTextColor(textColor)
                    arrayOf(
                        // senderPhotoLarge,
                        senderNameLarge,
                        senderNumberLarge
                    ).forEach {
                        it.setOnClickListener {
                            if (conversation != null) launchConversationDetails(threadId)
                        }
                    }
                    senderNameLarge.setOnLongClickListener { copyToClipboard(senderNameLarge.value); true }
                    senderNumberLarge.setOnLongClickListener { copyToClipboard(senderNumberLarge.value); true }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupSIMSelector() {
        val textColor = getProperTextColor()
        val availableSIMs = subscriptionManagerCompat().activeSubscriptionInfoList ?: return
        if (availableSIMs.size > 1) {
            availableSIMCards.clear()
            availableSIMs.forEachIndexed { index, subscriptionInfo ->
                var label = subscriptionInfo.displayName?.toString() ?: ""
                if (subscriptionInfo.number?.isNotEmpty() == true) {
                    label += " (${subscriptionInfo.number})"
                }
                val SIMCard = SIMCard(index + 1, subscriptionInfo.subscriptionId, label)
                availableSIMCards.add(SIMCard)
            }

            val numbers = ArrayList<String>()
            participants.forEach { contact ->
                contact.phoneNumbers.forEach {
                    numbers.add(it.normalizedNumber)
                }
            }

            if (numbers.isEmpty()) {
                return
            }

            currentSIMCardIndex = getProperSimIndex(availableSIMs, numbers)
            binding.messageHolder.threadSelectSimIcon.background.applyColorFilter(
                resources.getColor(com.goodwy.commons.R.color.activated_item_foreground, theme)
            )
            binding.messageHolder.threadSelectSimIcon.applyColorFilter(getProperTextColor())
            binding.messageHolder.threadSelectSimIconHolder.beVisibleIf(!config.showSimSelectionDialog)
            binding.messageHolder.threadSelectSimNumber.beVisible()
            val simLabel =
                if (availableSIMCards.size > currentSIMCardIndex) availableSIMCards[currentSIMCardIndex].label else "SIM Card"
            binding.messageHolder.threadSelectSimIconHolder.contentDescription = simLabel

            if (availableSIMCards.isNotEmpty()) {
                binding.messageHolder.threadSelectSimIconHolder.setOnClickListener {
                    currentSIMCardIndex = (currentSIMCardIndex + 1) % availableSIMCards.size
                    val currentSIMCard = availableSIMCards[currentSIMCardIndex]
                    @SuppressLint("SetTextI18n")
                    binding.messageHolder.threadSelectSimNumber.text = currentSIMCard.id.toString()
                    val simColor = if (!config.colorSimIcons) textColor
                    else {
                        val simId = currentSIMCard.id
                        if (simId in 1..4) config.simIconsColors[simId] else config.simIconsColors[0]
                    }
                    binding.messageHolder.threadSelectSimIcon.applyColorFilter(simColor)
                    val currentSubscriptionId = currentSIMCard.subscriptionId
                    numbers.forEach {
                        config.saveUseSIMIdAtNumber(it, currentSubscriptionId)
                    }
                    it.performHapticFeedback()
                    binding.messageHolder.threadSelectSimIconHolder.contentDescription = currentSIMCard.label
                    toast(currentSIMCard.label)
                    updateAvailableMessageCountForCurrentSim()
                }
            }

            binding.messageHolder.threadSelectSimNumber.setTextColor(textColor.getContrastColor())
            try {
                @SuppressLint("SetTextI18n")
                binding.messageHolder.threadSelectSimNumber.text = (availableSIMCards[currentSIMCardIndex].id).toString()
                val simColor =
                    if (!config.colorSimIcons) textColor
                    else {
                        val simId = availableSIMCards[currentSIMCardIndex].id
                        if (simId in 1..4) config.simIconsColors[simId] else config.simIconsColors[0]
                    }
                binding.messageHolder.threadSelectSimIcon.applyColorFilter(simColor)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
        updateAvailableMessageCountForCurrentSim()
    }

    private fun updateAvailableMessageCountForCurrentSim() {
        val slotId = FeeInfoUtils.getCurrentSimSlotId(
            context = this,
            availableSIMCards = availableSIMCards,
            currentSIMCardIndex = currentSIMCardIndex
        )
        Log.d(debugTag, "updateAvailableMessageCountForCurrentSim: resolved slotId=$slotId")
        if (slotId == null) {
            Log.d(debugTag, "updateAvailableMessageCountForCurrentSim: slotId is null, hiding view")
            binding.messageHolder.threadAvailableMessageCount.beGone()
            return
        }

        ensureBackgroundThread {
            val smsCount = FeeInfoUtils.getAvailableSmsCountForSlot(this, slotId)
            Log.d(debugTag, "updateAvailableMessageCountForCurrentSim: slotId=$slotId, smsCount=$smsCount")
            runOnUiThread {
                val countView = binding.messageHolder.threadAvailableMessageCount
                if (smsCount == null) {
                    countView.beGone()
                } else {
                    countView.text = getString(R.string.available_sms_count, smsCount)
                    countView.setTextColor(getProperTextColor())
                    countView.beVisible()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getProperSimIndex(
        availableSIMs: MutableList<SubscriptionInfo>,
        numbers: List<String>,
    ): Int {
        val userPreferredSimId = config.getUseSIMIdAtNumber(numbers.first())
        val userPreferredSimIdx =
            availableSIMs.indexOfFirstOrNull { it.subscriptionId == userPreferredSimId }

        val lastMessage = messages.lastOrNull()
        val senderPreferredSimIdx = if (lastMessage?.isReceivedMessage() == true) {
            availableSIMs.indexOfFirstOrNull { it.subscriptionId == lastMessage.subscriptionId }
        } else {
            null
        }

        val defaultSmsSubscriptionId = SmsManager.getDefaultSmsSubscriptionId()
        val systemPreferredSimIdx = if (defaultSmsSubscriptionId >= 0) {
            availableSIMs.indexOfFirstOrNull { it.subscriptionId == defaultSmsSubscriptionId }
        } else {
            null
        }

        return userPreferredSimIdx ?: senderPreferredSimIdx ?: systemPreferredSimIdx ?: 0
    }

//    private fun tryBlocking() {
//        if (isOrWasThankYouInstalled()) {
//            blockNumber()
//        } else {
//            FeatureLockedDialog(this) { }
//        }
//    }

    private fun isBlockNumbers(): Boolean {
        return participants.getAddresses().any { isNumberBlocked(it, getBlockedNumbers()) }
    }

    private fun blockNumber() {
        val numbers = participants.getAddresses()
        val numbersString = TextUtils.join(", ", numbers)
        val isBlockNumbers = isBlockNumbers()
        val baseString =
            if (isBlockNumbers) com.goodwy.strings.R.string.unblock_confirmation
            else com.goodwy.commons.R.string.block_confirmation
        val question = String.format(resources.getString(baseString), numbersString)

        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        ConfirmationDialog(this, question, blurTarget = blurTarget) {
            ensureBackgroundThread {
                numbers.forEach {
                    if (isBlockNumbers) {
                        deleteBlockedNumber(it)
                        runOnUiThread { refreshMenuItems()}
                    } else {
                        addBlockedNumber(it)
                        runOnUiThread { refreshMenuItems()}
                    }
                }
                refreshConversations()
                //finish()
            }
        }
    }

    private fun askConfirmDelete() {
        val confirmationMessage = R.string.delete_whole_conversation_confirmation
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        ConfirmationDialog(this, getString(confirmationMessage), blurTarget = blurTarget) {
            ensureBackgroundThread {
                if (isRecycleBin) {
                    emptyMessagesRecycleBinForConversation(threadId)
                } else {
                    deleteConversation(threadId)
                }
                runOnUiThread {
                    refreshConversations()
                    finish()
                }
            }
        }
    }

    private fun askConfirmRestoreAll() {
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        ConfirmationDialog(this, getString(R.string.restore_confirmation), blurTarget = blurTarget) {
            ensureBackgroundThread{
                restoreAllMessagesFromRecycleBinForConversation(threadId)
                runOnUiThread {
                    refreshConversations()
                    finish()
                }
            }
        }
    }

    private fun archiveConversation() {
        ensureBackgroundThread {
            updateConversationArchivedStatus(threadId, true)
            runOnUiThread {
                refreshConversations()
                finish()
            }
        }
    }

    private fun unarchiveConversation() {
        ensureBackgroundThread {
            updateConversationArchivedStatus(threadId, false)
            runOnUiThread {
                refreshConversations()
                finish()
            }
        }
    }

    private fun dialNumber() {
        val phoneNumber = participants.first().phoneNumbers.first().normalizedNumber
        dialNumber(phoneNumber)
    }

    private fun markAsUnread() {
        ensureBackgroundThread {
            conversationsDB.markUnread(threadId)
            markThreadMessagesUnread(threadId)
            runOnUiThread {
                finish()
                bus?.post(Events.RefreshConversations())
            }
        }
    }

    private fun addNumberToContact() {
        val phoneNumber =
            participants.firstOrNull()?.phoneNumbers?.firstOrNull()?.normalizedNumber ?: return
        Intent().apply {
            action = Intent.ACTION_INSERT_OR_EDIT
            type = "vnd.android.cursor.item/contact"
            putExtra(KEY_PHONE, phoneNumber)
            launchActivityIntent(this)
        }
    }

    @SuppressLint("MissingPermission")
    private fun renameConversation() {
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        RenameConversationDialog(this, conversation!!, blurTarget) { title ->
            ensureBackgroundThread {
                conversation = renameConversation(conversation!!, newTitle = title)
                runOnUiThread {
                    setupThreadTitle()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getThreadItems(): ArrayList<ThreadItem> {
        val items = ArrayList<ThreadItem>()
        if (isFinishing) {
            return items
        }

        messages.sortBy { it.date }

        val subscriptionIdToSimId = HashMap<Int, String>()
        subscriptionIdToSimId[-1] = "?"
        subscriptionManagerCompat().activeSubscriptionInfoList?.forEachIndexed { index, subscriptionInfo ->
            subscriptionIdToSimId[subscriptionInfo.subscriptionId] = "${index + 1}"
        }

        var prevDateTime = 0
        var prevSIMId = -2
        var hadUnreadItems = false
        val cnt = messages.size
        for (i in 0 until cnt) {
            val message = messages.getOrNull(i) ?: continue
            // do not show the date/time above every message, only if the difference between the 2 messages is at least MIN_DATE_TIME_DIFF_SECS,
            // or if the message is sent from a different SIM
            val isSentFromDifferentKnownSIM =
                prevSIMId != -1 && message.subscriptionId != -1 && prevSIMId != message.subscriptionId
            if (message.date - prevDateTime > MIN_DATE_TIME_DIFF_SECS || isSentFromDifferentKnownSIM) {
                val simCardID = subscriptionIdToSimId[message.subscriptionId] ?: "?"
                items.add(ThreadDateTime(message.date, simCardID))
                prevDateTime = message.date
            }
            items.add(message)

            if (message.type == Telephony.Sms.MESSAGE_TYPE_FAILED) {
                items.add(ThreadError(message.id, message.body))
            }

            if (message.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX) {
                items.add(ThreadSending(message.id))
            }

            if (!message.read) {
                hadUnreadItems = true
                markMessageRead(message.id, message.isMMS)
                conversationsDB.markRead(threadId)
            }

            if (i == cnt - 1 && (message.type == Telephony.Sms.MESSAGE_TYPE_SENT)) {
                items.add(
                    ThreadSent(
                        messageId = message.id,
                        delivered = message.status == Telephony.Sms.STATUS_COMPLETE
                    )
                )
            }
            prevSIMId = message.subscriptionId
        }

        if (hadUnreadItems) {
            bus?.post(Events.RefreshConversations())
        }

        return items
    }

    private fun launchActivityForResult(
        intent: Intent,
        requestCode: Int,
        @StringRes error: Int = com.goodwy.commons.R.string.no_app_found,
    ) {
        hideKeyboard()
        try {
            startActivityForResult(intent, requestCode)
        } catch (_: ActivityNotFoundException) {
            showErrorToast(getString(error))
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun getAttachmentsDir(): File {
        return File(cacheDir, "attachments").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private fun launchCapturePhotoIntent() {
        val imageFile = File.createTempFile("attachment_", ".jpg", getAttachmentsDir())
        val capturedImageUri = getMyFileUri(imageFile)
        messageHolderHelper?.setCapturedImageUri(capturedImageUri)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, capturedImageUri)
        }
        launchActivityForResult(intent, MessageHolderHelper.CAPTURE_PHOTO_INTENT)
    }

    private fun launchCaptureVideoIntent() {
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        launchActivityForResult(intent, MessageHolderHelper.CAPTURE_VIDEO_INTENT)
    }

    private fun launchCaptureAudioIntent() {
        val intent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        launchActivityForResult(intent, MessageHolderHelper.CAPTURE_AUDIO_INTENT)
    }

    private fun launchGetContentIntent(mimeTypes: Array<String>, requestCode: Int) {
        Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            launchActivityForResult(this, requestCode)
        }
    }

    private fun launchPickContactIntent() {
        Intent(Intent.ACTION_PICK).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            launchActivityForResult(this, MessageHolderHelper.PICK_CONTACT_INTENT)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addContactAttachment(contactUri: Uri) {
        val items = arrayListOf(
            RadioItem(1, getString(com.goodwy.commons.R.string.file)),
            RadioItem(2, getString(com.goodwy.commons.R.string.text))
        )

        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        RadioGroupDialog(this@ThreadActivity, items, blurTarget = blurTarget) {
            val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            if (it == 1) {
                ContactsHelper(this).getContacts(showOnlyContactsWithNumbers = false) { contacts ->
                    val contact = if (contactUri.pathSegments.last().startsWith("local_")) {
                        val contactId = contactUri.path!!.substringAfter("local_").toInt()
                        try {
                            val privateContacts = MyContactsContentProvider.getContacts(this, privateCursor)
                            privateContacts.firstOrNull { it.id == contactId }
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        val contactId = getContactUriRawId(contactUri)
                        contacts.firstOrNull { it.id == contactId }
                    }

                    if (contact != null) {
                        val outputFile = File(getAttachmentsDir(), "${contact.contactId}.vcf")
                        val outputStream = outputFile.outputStream()

                        VcfExporter().exportContacts(
                            activity = this,
                            outputStream = outputStream,
                            contacts = arrayListOf(contact),
                            showExportingToast = false,
                        ) {
                            if (it == ExportResult.EXPORT_OK) {
                                val vCardUri = getMyFileUri(outputFile)
                                runOnUiThread {
                                    addAttachment(vCardUri)
                                }
                            } else {
                                toast(com.goodwy.commons.R.string.unknown_error_occurred)
                            }
                        }
                    } else {
                        toast(com.goodwy.commons.R.string.unknown_error_occurred)
                    }
                }
            } else {
                ContactsHelper(this).getContacts(showOnlyContactsWithNumbers = false) { contacts ->
                    val contact = if (contactUri.pathSegments.last().startsWith("local_")) {
                        val contactId = contactUri.path!!.substringAfter("local_").toInt()
                        try {
                            val privateContacts = MyContactsContentProvider.getContacts(this, privateCursor)
                            privateContacts.firstOrNull { it.id == contactId }
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        val contactId = getContactUriRawId(contactUri)
                        contacts.firstOrNull { it.id == contactId }
                    }

                    if (contact != null) {
                        runOnUiThread {
                            binding.messageHolder.threadTypeMessage.setText(binding.messageHolder.threadTypeMessage.value + contact.getContactToText(this))
                        }
                    }
                }
            }
        }
    }

    private fun addAttachment(uri: Uri) {
        messageHolderHelper?.addAttachment(uri)
    }

    private fun saveAttachments(resultData: Intent) {
        applicationContext.contentResolver.takePersistableUriPermission(
            resultData.data!!, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val destinationUri = resultData.data ?: return
        ensureBackgroundThread {
            try {
                if (DocumentsContract.isTreeUri(destinationUri)) {
                    val outputDir = DocumentFile.fromTreeUri(this, destinationUri)
                        ?: return@ensureBackgroundThread
                    pendingAttachmentsToSave?.forEach { attachment ->
                        val documentFile = outputDir.createFile(
                            attachment.mimetype,
                            attachment.filename.takeIf { it.isNotBlank() }
                                ?: attachment.uriString.getFilenameFromPath()
                        ) ?: return@forEach
                        copyToUri(src = attachment.getUri(), dst = documentFile.uri)
                    }
                } else {
                    copyToUri(pendingAttachmentsToSave!!.first().getUri(), resultData.data!!)
                }

                toast(com.goodwy.commons.R.string.file_saved)
            } catch (e: Exception) {
                showErrorToast(e)
            } finally {
                pendingAttachmentsToSave = null
            }
        }
    }

    private fun checkSendMessageAvailability() {
        messageHolderHelper?.checkSendMessageAvailability()
    }

    private fun sendMessage() {
        val text = messageHolderHelper?.getMessageText() ?: ""
        if (text.isEmpty() && (messageHolderHelper?.getAttachmentSelections()?.isEmpty() != false)) {
            showErrorToast(getString(com.goodwy.commons.R.string.unknown_error_occurred))
            return
        }
        scrollToBottom()

        val processedText = removeDiacriticsIfNeeded(text)
        val subscriptionId = availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
            ?: SmsManager.getDefaultSmsSubscriptionId()

        if (config.showSimSelectionDialog && availableSIMCards.size > 1) {
            val items: ArrayList<RadioItem> = arrayListOf()
            items.clear()
            availableSIMCards.forEach {
                val simColor = if (it.id in 1..4) config.simIconsColors[it.id] else config.simIconsColors[0]
                val res = when (it.id) {
                    1 -> R.drawable.ic_sim_one
                    2 -> R.drawable.ic_sim_two
                    else -> R.drawable.ic_sim_vector
                }
                val drawable = ResourcesCompat.getDrawable(resources, res, theme)?.apply {
                    applyColorFilter(simColor)
                }
                items.add(RadioItem(it.id, it.label, it, drawable = drawable))
            }
            val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupIconDialog(this@ThreadActivity, items, blurTarget = blurTarget) {
                val simId = (it as SIMCard).subscriptionId
                if (isScheduledMessage) {
                    sendScheduledMessage(processedText, simId)
                } else {
                    sendNormalMessage(processedText, simId)
                }
            }
        } else {
            if (isScheduledMessage) {
                sendScheduledMessage(processedText, subscriptionId)
            } else {
                sendNormalMessage(processedText, subscriptionId)
            }
        }
    }
    

    private fun sendScheduledMessage(text: String, subscriptionId: Int) {
        if (scheduledDateTime.millis < System.currentTimeMillis() + 1000L) {
            toast(R.string.must_pick_time_in_the_future)
            launchScheduleSendDialog(scheduledDateTime)
            return
        }

        refreshedSinceSent = false
        try {
            ensureBackgroundThread {
                val messageId = scheduledMessage?.id ?: generateRandomId()
                val message = buildScheduledMessage(text, subscriptionId, messageId)
                if (messages.isEmpty()) {
                    // create a temporary thread until a real message is sent
                    threadId = message.threadId
                    createTemporaryThread(message, message.threadId, conversation)
                }
                val conversation = conversationsDB.getConversationWithThreadId(threadId)
                if (conversation != null) {
                    val nowSeconds = (System.currentTimeMillis() / 1000).toInt()
                    conversationsDB.insertOrUpdate(
                        conversation.copy(
                            date = nowSeconds,
                            snippet = message.body
                        )
                    )
                }
                scheduleMessage(message)
                insertOrUpdateMessage(message)

                runOnUiThread {
                    clearCurrentMessage()
                    hideScheduleSendUi()
                    scheduledMessage = null
                }
            }
        } catch (e: Exception) {
            showErrorToast(
                e.localizedMessage ?: getString(com.goodwy.commons.R.string.unknown_error_occurred)
            )
        }
    }

    private fun sendNormalMessage(text: String, subscriptionId: Int) {
        val addresses = participants.getAddresses()
        val attachments = messageHolderHelper?.buildMessageAttachments() ?: emptyList()

        try {
            refreshedSinceSent = false
            sendMessageCompat(text, addresses, subscriptionId, attachments, messageToResend)
            ensureBackgroundThread {
                val messages = getMessages(threadId, limit = maxOf(1, attachments.size))
                    .filterNotInByKey(messages) { it.getStableId() }
                for (message in messages) {
                    insertOrUpdateMessage(message)
                }
            }
            clearCurrentMessage()

        } catch (e: Exception) {
            showErrorToast(e)
        } catch (e: Error) {
            showErrorToast(
                e.localizedMessage ?: getString(com.goodwy.commons.R.string.unknown_error_occurred)
            )
        }
    }

    private fun clearCurrentMessage() {
        messageHolderHelper?.clearMessage()
    }

    private fun insertOrUpdateMessage(message: Message) {
        if (messages.map { it.id }.contains(message.id)) {
            val messageToReplace = messages.find { it.id == message.id }
            messages[messages.indexOf(messageToReplace)] = message
        } else {
            messages.add(message)
        }

        val newItems = getThreadItems()
        runOnUiThread {
            getOrCreateThreadAdapter().updateMessages(newItems, newItems.lastIndex)
            if (!refreshedSinceSent) {
                refreshMessages()
            }
        }
        messagesDB.insertOrUpdate(message)
        if (shouldUnarchive()) {
            updateConversationArchivedStatus(message.threadId, false)
            refreshConversations()
        }
    }

    private fun getPhoneNumbersFromIntent(): ArrayList<String> {
        val numberFromIntent = intent.getStringExtra(THREAD_NUMBER)
        val numbers = ArrayList<String>()

        if (numberFromIntent != null) {
            if (numberFromIntent.startsWith('[') && numberFromIntent.endsWith(']')) {
                val type = object : TypeToken<List<String>>() {}.type
                numbers.addAll(Gson().fromJson(numberFromIntent, type))
            } else {
                numbers.add(numberFromIntent)
            }
        }
        return numbers
    }

    private fun fixParticipantNumbers(
        participants: ArrayList<SimpleContact>,
        properNumbers: ArrayList<String>,
    ): ArrayList<SimpleContact> {
        for (number in properNumbers) {
            for (participant in participants) {
                participant.phoneNumbers = participant.phoneNumbers.map {
                    val numberWithoutPlus = number.replace("+", "")
                    if (numberWithoutPlus == it.normalizedNumber.trim()) {
                        if (participant.name == it.normalizedNumber) {
                            participant.name = number
                        }
                        PhoneNumber(number, 0, "", number)
                    } else {
                        PhoneNumber(it.normalizedNumber, 0, "", it.normalizedNumber)
                    }
                } as ArrayList<PhoneNumber>
            }
        }

        return participants
    }

    fun saveMMS(attachments: List<Attachment>) {
        pendingAttachmentsToSave = attachments
        if (attachments.size == 1) {
            val attachment = attachments.first()
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = attachment.mimetype
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, attachment.uriString.split("/").last())
                launchActivityForResult(
                    intent = this,
                    requestCode = PICK_SAVE_FILE_INTENT,
                    error = com.goodwy.commons.R.string.system_service_disabled
                )
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                launchActivityForResult(
                    intent = this,
                    requestCode = PICK_SAVE_DIR_INTENT,
                    error = com.goodwy.commons.R.string.system_service_disabled
                )
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun refreshMessages(@Suppress("unused") event: Events.RefreshMessages) {
        if (isRecycleBin) {
            return
        }

        refreshedSinceSent = true
        allMessagesFetched = false

        if (isActivityVisible) {
            notificationManager.cancel(threadId.hashCode())
        }

        val lastMaxId = messages.filterNot { it.isScheduled }.maxByOrNull { it.id }?.id ?: 0L
        val newThreadId = getThreadId(participants.getAddresses().toSet())
        val newMessages = getMessages(newThreadId, includeScheduledMessages = false)

        if (messages.isNotEmpty() && messages.all { it.isScheduled } && newMessages.isNotEmpty()) {
            // update scheduled messages with real thread id
            threadId = newThreadId
            updateScheduledMessagesThreadId(
                messages = messages.filter { it.threadId != threadId },
                newThreadId = threadId
            )
        }

        messages = newMessages.apply {
            val scheduledMessages = messagesDB.getScheduledThreadMessages(threadId)
                .filterNot { it.isScheduled && it.millis() < System.currentTimeMillis() }
            addAll(scheduledMessages)
            if (config.useRecycleBin) {
                val recycledMessages = messagesDB.getThreadMessagesFromRecycleBin(threadId).toSet()
                removeAll(recycledMessages)
            }
        }

        messages.filter { !it.isScheduled && !it.isReceivedMessage() && it.id > lastMaxId }
            .forEach { latestMessage ->
                messagesDB.insertOrIgnore(latestMessage)
            }

        setupAdapter()
        runOnUiThread {
            setupSIMSelector()
        }
    }

    private fun isMmsMessage(text: String): Boolean {
        val isGroupMms = participants.size > 1 && config.sendGroupMessageMMS
        val isLongMmsMessage = isLongMmsMessage(text)
        return (messageHolderHelper?.getAttachmentSelections()?.isNotEmpty() == true) || isGroupMms || isLongMmsMessage
    }

//    private fun updateMessageType() {
//        val text = binding.messageHolder.threadTypeMessage.text.toString()
//        val stringId = if (isMmsMessage(text)) {
//            R.string.mms
//        } else {
//            R.string.sms
//        }
//        //binding.messageHolder.threadSendMessage.setText(stringId)
//    }

    private fun showScheduledMessageInfo(message: Message) {
        val items = arrayListOf(
            RadioItem(TYPE_EDIT, getString(R.string.update_message)),
            RadioItem(TYPE_SEND, getString(R.string.send_now)),
            RadioItem(TYPE_DELETE, getString(com.goodwy.commons.R.string.delete))
        )
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        RadioGroupDialog(
            activity = this,
            items = items,
            titleId = R.string.scheduled_message,
            blurTarget = blurTarget
        ) { any ->
            when (any as Int) {
                TYPE_DELETE -> cancelScheduledMessageAndRefresh(message.id)
                TYPE_EDIT -> editScheduledMessage(message)
                TYPE_SEND -> {
                    messages.removeAll { message.id == it.id }
                    extractAttachments(message)
                    sendNormalMessage(message.body, message.subscriptionId)
                    cancelScheduledMessageAndRefresh(message.id)
                }
            }
        }
    }

    private fun extractAttachments(message: Message) {
        val messageAttachment = message.attachment
        if (messageAttachment != null) {
            for (attachment in messageAttachment.attachments) {
                addAttachment(attachment.getUri())
            }
        }
    }

    private fun editScheduledMessage(message: Message) {
        scheduledMessage = message
        clearCurrentMessage()
        binding.messageHolder.threadTypeMessage.setText(message.body)
        extractAttachments(message)
        scheduledDateTime = DateTime(message.millis()) //TODO Persian date
        showScheduleMessageDialog()
    }

    private fun cancelScheduledMessageAndRefresh(messageId: Long) {
        ensureBackgroundThread {
            deleteScheduledMessage(messageId)
            cancelScheduleSendPendingIntent(messageId)
            refreshMessages()
        }
    }

    private fun launchScheduleSendDialog(originalDateTime: DateTime? = null) {
        askForExactAlarmPermissionIfNeeded {
            val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            ScheduleMessageDialog(this, originalDateTime, blurTarget) { newDateTime ->
                if (newDateTime != null) {
                    scheduledDateTime = newDateTime
                    showScheduleMessageDialog()
                }
            }
        }
    }

//    private fun isPlayServicesAvailable(): Boolean {
//        val googleAPI = GoogleApiAvailability.getInstance()
//        val result = googleAPI.isGooglePlayServicesAvailable(applicationContext)
//        return result == ConnectionResult.SUCCESS
//    }

    private fun setupScheduleSendUi() = binding.messageHolder.apply {
        val textColor = getProperTextColor()
        scheduledMessageHolder.background.applyColorFilter(getProperPrimaryColor().darkenColor())
        scheduledMessageIcon.applyColorFilter(textColor)
        scheduledMessageButton.apply {
            setTextColor(textColor)
//            setOnClickListener {
//                launchScheduleSendDialog(scheduledDateTime)
//            }
        }
        scheduledMessagePress.setOnClickListener {
            launchScheduleSendDialog(scheduledDateTime)
        }

        discardScheduledMessage.apply {
            applyColorFilter(textColor)
            setOnClickListener {
                hideScheduleSendUi()
                if (scheduledMessage != null) {
                    cancelScheduledMessageAndRefresh(scheduledMessage!!.id)
                    scheduledMessage = null
                }
            }
        }
    }

    private fun showScheduleMessageDialog() {
        isScheduledMessage = true
        updateSendButtonDrawable()
        binding.messageHolder.scheduledMessageHolder.beVisible()

        val dateTime = scheduledDateTime
        val millis = dateTime.millis
        binding.messageHolder.scheduledMessageButton.text =
            if (dateTime.yearOfCentury().get() > DateTime.now().yearOfCentury().get()) {
                millis.formatDate(this)
            } else {
                val flags = FORMAT_SHOW_TIME or FORMAT_SHOW_DATE or FORMAT_NO_YEAR
                DateUtils.formatDateTime(this, millis, flags)
            }
    }

    private fun hideScheduleSendUi() {
        isScheduledMessage = false
        binding.messageHolder.scheduledMessageHolder.beGone()
        updateSendButtonDrawable()
    }

    private fun updateSendButtonDrawable() {
        messageHolderHelper?.setScheduledMessage(isScheduledMessage)
        messageHolderHelper?.updateSendButtonDrawable()
    }

    private fun buildScheduledMessage(text: String, subscriptionId: Int, messageId: Long): Message {
        val threadId = if (messages.isEmpty()) messageId else threadId
        return Message(
            id = messageId,
            body = text,
            type = MESSAGE_TYPE_QUEUED,
            status = STATUS_NONE,
            participants = participants,
            date = (scheduledDateTime.millis / 1000).toInt(),
            read = false,
            threadId = threadId,
            isMMS = isMmsMessage(text),
            attachment = MessageAttachment(messageId, text, messageHolderHelper?.buildMessageAttachments(messageId) ?: arrayListOf()),
            senderPhoneNumber = "",
            senderName = "",
            senderPhotoUri = "",
            subscriptionId = subscriptionId,
            isScheduled = true
        )
    }


    private fun showAttachmentPicker() {
        messageHolderHelper?.showAttachmentPicker()
    }

    private fun maybeSetupRecycleBinView() {
        if (isRecycleBin) {
            binding.messageHolder.root.beGone()
        }
    }

    private fun hideAttachmentPicker() {
        messageHolderHelper?.hideAttachmentPicker()
    }

    private fun getBottomBarColor() = if (isDynamicTheme()) {
        getColoredMaterialStatusBarColor() //resources.getColor(R.color.you_bottom_bar_color)
    } else {
        getColoredMaterialStatusBarColor()
    }

    fun setupMessagingEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.messageHolder.threadTypeMessage
        ) { view, insets ->
            val type = WindowInsetsCompat.Type.ime()
            val isKeyboardVisible = insets.isVisible(type)
            if (isKeyboardVisible) {
                val keyboardHeight = insets.getInsets(type).bottom
                val bottomBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom

                // check keyboard height just to be sure, 150 seems like a good middle ground between ime and navigation bar
                config.keyboardHeight = if (keyboardHeight > 150) {
                    keyboardHeight - bottomBarHeight
                } else {
                    getDefaultKeyboardHeight()
                }
                // Only hide the attachment picker when the keyboard *just* became visible (e.g. user focused the input).
                // When the user taps the attachment button while the keyboard is already visible, we must not hide the picker.
                if (!wasKeyboardVisible) {
                    hideAttachmentPicker()
                    isAttachmentPickerVisible = false
                }
                wasKeyboardVisible = true
            } else {
                wasKeyboardVisible = false
                if (isAttachmentPickerVisible) {
                    showAttachmentPicker()
                }
            }

            insets
        }
    }

    companion object {
        private const val ACTION_FEE_INFO_SET = "com.chonha.total.action.ACTION_FEE_INFO_SET"
        const val TYPE_EDIT = 14
        const val TYPE_SEND = 15
        const val TYPE_DELETE = 16
        const val MIN_DATE_TIME_DIFF_SECS = 300
        const val SCROLL_TO_BOTTOM_FAB_LIMIT = 20
        const val PREFETCH_THRESHOLD = 45
        const val PICK_SAVE_FILE_INTENT = 1008
        const val PICK_SAVE_DIR_INTENT = 1009
    }

    private fun updateContactImage() {
        val senderPhoto = when (config.threadTopStyle) {
            THREAD_TOP_COMPACT -> binding.topDetailsCompact.senderPhoto
            // THREAD_TOP_LARGE -> binding.senderPhotoLarge
            THREAD_TOP_LARGE -> null
            else -> binding.topDetailsCompact.senderPhoto
        }

        if (senderPhoto == null) return

        val title = conversation?.title
        var threadTitle = if (!title.isNullOrEmpty()) {
            title
        } else {
            participants.getThreadTitle(this@ThreadActivity)
        }
        if (threadTitle.isEmpty()) threadTitle = intent.getStringExtra(THREAD_TITLE) ?: ""

        if (conversation != null && (!isDestroyed || !isFinishing)) {
            if ((threadTitle == conversation!!.phoneNumber || conversation!!.isCompany) && conversation!!.photoUri == "") {
                val drawable =
                    if (conversation!!.isCompany) SimpleContactsHelper(this@ThreadActivity).getColoredCompanyIcon(conversation!!.title)
                    else SimpleContactsHelper(this@ThreadActivity).getColoredContactIcon(conversation!!.title)
                senderPhoto.setImageDrawable(drawable)
            } else {
                val placeholder = if (participants.size > 1) {
                    SimpleContactsHelper(this).getColoredGroupIcon(threadTitle)
                } else {
                    null
                }

                SimpleContactsHelper(this).loadContactImage(conversation!!.photoUri, senderPhoto, threadTitle, placeholder)
            }
        } else {
            if (!isDestroyed || !isFinishing) {
                val placeholder = if (participants.size > 1) {
                    SimpleContactsHelper(this).getColoredGroupIcon(threadTitle)
                } else {
                    null
                }

                val number = intent.getStringExtra(THREAD_NUMBER)
                var namePhoto: NamePhoto? = null
                if (number != null) {
                    namePhoto = getNameAndPhotoFromPhoneNumber(number)
                }
                var threadUri = intent.getStringExtra(THREAD_URI) ?: ""
                if (threadUri == "" && namePhoto != null) {
                    threadUri = namePhoto.photoUri ?: ""
                }
                if (threadTitle.isEmpty() && namePhoto != null) threadTitle = namePhoto.name
                SimpleContactsHelper(this).loadContactImage(threadUri, senderPhoto, threadTitle, placeholder)
            }
        }
    }


    private fun showExpandedMessageFragment() {
        val currentText = binding.messageHolder.threadTypeMessage.text?.toString() ?: ""
        expandedMessageFragment = com.android.mms.fragments.ExpandedMessageFragment.newInstance(currentText)
        
        expandedMessageFragment?.setOnMessageTextChangedListener { text ->
            binding.messageHolder.threadTypeMessage.setText(text)
        }
        
        expandedMessageFragment?.setOnSendMessageListener {
            val text = expandedMessageFragment?.getMessageText() ?: ""
            binding.messageHolder.threadTypeMessage.setText(text)
            hideExpandedMessageFragment()
            sendMessage()
        }
        
        expandedMessageFragment?.setOnMinimizeListener {
            val text = expandedMessageFragment?.getMessageText() ?: ""
            binding.messageHolder.threadTypeMessage.setText(text)
            hideExpandedMessageFragment()
        }
        
        // Update fragment thread title after fragment is created
        expandedMessageFragment?.let { fragment ->
            // Set up lifecycle observer BEFORE committing transaction to ensure it catches the lifecycle events
            val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                    // Update when fragment resumes (view is guaranteed to be created by then)
                    updateFragmentThreadTitle(fragment)
                    fragment.lifecycle.removeObserver(this)
                }
            }
            fragment.lifecycle.addObserver(observer)
            
            // Hide the main content and show the fragment container (sibling of thread_coordinator)
            findViewById<View>(R.id.thread_coordinator)?.beGone()
            findViewById<View>(R.id.fragment_container)?.beVisible()
            
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit()
            
            // Also try immediate update if view is already available (for faster execution)
            // Use postDelayed to give the fragment time to create its view
            fragment.view?.post {
                updateFragmentThreadTitle(fragment)
            } ?: run {
                // If view is null, post with a small delay
                Handler(android.os.Looper.getMainLooper()).postDelayed({
                    fragment.view?.post {
                        updateFragmentThreadTitle(fragment)
                    }
                }, 100)
            }
        }
    }
    
    private fun updateFragmentThreadTitle(fragment: com.android.mms.fragments.ExpandedMessageFragment) {
        if (fragment.view == null) return
        
        val title = conversation?.title
        // Match setupThreadTitle(): for multiple participants use "Name and N others", else use conversation title or getThreadTitle
        val threadTitle = if (participants.size > 1) {
            participants.getThreadTitle(this@ThreadActivity)
        } else {
            if (!title.isNullOrEmpty()) title else participants.getThreadTitle(this@ThreadActivity)
        }
        val threadSubtitle = participants.getThreadSubtitle(this@ThreadActivity)
        fragment.updateThreadTitle(
            threadTitle = threadTitle,
            threadSubtitle = threadSubtitle,
            threadTopStyle = config.threadTopStyle,
            showContactThumbnails = config.showContactThumbnails,
            conversationPhotoUri = conversation?.photoUri,
            conversationTitle = conversation?.title,
            conversationPhoneNumber = conversation?.phoneNumber,
            isCompany = conversation?.isCompany ?: false,
            participantsCount = participants.size
        )
    }

    private fun hideExpandedMessageFragment() {
        expandedMessageFragment?.let {
            supportFragmentManager.popBackStack()
            findViewById<View>(R.id.fragment_container)?.beGone()
            findViewById<View>(R.id.thread_coordinator)?.beVisible()
            expandedMessageFragment = null
        }
    }
    
    override fun onBackPressedCompat(): Boolean {
        return if (expandedMessageFragment != null) {
            hideExpandedMessageFragment()
            true
        } else {
            false
        }
    }
}
