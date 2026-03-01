package com.android.mms.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updateLayoutParams
import androidx.appcompat.content.res.AppCompatResources
import com.behaviorule.arturdumchev.library.pixels
import com.goodwy.commons.activities.ManageBlockedNumbersActivity
import com.goodwy.commons.dialogs.*
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.RadioItem
import com.goodwy.commons.models.Release
import com.android.mms.BuildConfig
import com.android.mms.R
import com.android.mms.databinding.ActivitySettingsBinding
import com.android.mms.dialogs.ExportMessagesDialog
import com.android.mms.extensions.*
import com.android.mms.helpers.*
import com.mikhaellopez.rxanimation.RxAnimation
import com.mikhaellopez.rxanimation.shake
import com.android.common.view.MVSideFrame
import com.goodwy.commons.views.BlurAppBarLayout
import eightbitlab.com.blurview.BlurTarget
import kotlin.math.abs
import kotlin.system.exitProcess
import java.util.Calendar
import java.util.Locale

class SettingsActivity : SimpleActivity() {
    private var blockedNumbersAtPause = -1
    private var recycleBinMessages = 0
    private var currentlyPlayingRingtone: android.media.Ringtone? = null
    private val messagesFileType = "application/json"
    private val messageImportFileTypes = buildList {
        add("application/json")
        add("application/xml")
        add("text/xml")
        if (!isQPlus()) {
            add("application/octet-stream")
        }
    }

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                MessagesImporter(this).importMessages(uri)
            }
        }

    private var exportMessagesDialog: ExportMessagesDialog? = null

    private val saveDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(messagesFileType)) { uri ->
            if (uri != null) {
                toast(com.goodwy.commons.R.string.exporting)
                exportMessagesDialog?.exportMessages(uri)
            }
        }

    private val notificationSoundPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val extras = result.data?.extras
                if (extras?.containsKey(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) == true) {
                    val uri = extras.getParcelable<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                    if (uri != null) {
                        config.notificationSound = uri.toString()
                        updateNotificationSoundDisplay()
                        // Update system notification sound
                        updateSystemNotificationSound(uri)
                        // Play the selected sound
                        try {
                            stopCurrentlyPlayingRingtone()
                            val ringtone = RingtoneManager.getRingtone(this, uri)
                            ringtone?.play()
                            currentlyPlayingRingtone = ringtone
                        } catch (e: Exception) {
                            showErrorToast(e)
                        }
                    } else {
                        // Silent was selected
                        config.notificationSound = SILENT
                        updateNotificationSoundDisplay()
                        // Update system notification sound to null (silent)
                        updateSystemNotificationSound(null)
                    }
                }
            }
        }

    private val deliveryReportSoundPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val extras = result.data?.extras
                if (extras?.containsKey(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) == true) {
                    val uri = extras.getParcelable<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                    if (uri != null) {
                        config.deliveryReportSound = uri.toString()
                        updateDeliveryReportSoundDisplay()
                        // Play the selected sound
                        try {
                            stopCurrentlyPlayingRingtone()
                            val ringtone = RingtoneManager.getRingtone(this, uri)
                            ringtone?.play()
                            currentlyPlayingRingtone = ringtone
                        } catch (e: Exception) {
                            showErrorToast(e)
                        }
                    } else {
                        // Silent was selected
                        config.deliveryReportSound = SILENT
                        updateDeliveryReportSoundDisplay()
                    }
                }
            }
        }


    private val binding by viewBinding(ActivitySettingsBinding::inflate)

    private val productIdX1 = BuildConfig.PRODUCT_ID_X1
    private val productIdX2 = BuildConfig.PRODUCT_ID_X2
    private val productIdX3 = BuildConfig.PRODUCT_ID_X3
    private val subscriptionIdX1 = BuildConfig.SUBSCRIPTION_ID_X1
    private val subscriptionIdX2 = BuildConfig.SUBSCRIPTION_ID_X2
    private val subscriptionIdX3 = BuildConfig.SUBSCRIPTION_ID_X3
    private val subscriptionYearIdX1 = BuildConfig.SUBSCRIPTION_YEAR_ID_X1
    private val subscriptionYearIdX2 = BuildConfig.SUBSCRIPTION_YEAR_ID_X2
    private val subscriptionYearIdX3 = BuildConfig.SUBSCRIPTION_YEAR_ID_X3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initTheme()
        initMVSideFrames()
        makeSystemBarsToTransparent()
        initBouncy()
        initBouncyListener()
        setupOptionsMenu()
        setupSettingsTopAppBar()

        if (config.changeColourTopBar) {
            val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
            setupSearchMenuScrollListener(
                scrollingView = binding.settingsNestedScrollview,
                searchMenu = binding.settingsMenu,
                surfaceColor = useSurfaceColor
            )
        }

        val iapList: ArrayList<String> = arrayListOf(productIdX1, productIdX2, productIdX3)
        val subList: ArrayList<String> =
            arrayListOf(
                subscriptionIdX1, subscriptionIdX2, subscriptionIdX3,
                subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3
            )
        val ruStoreList: ArrayList<String> =
            arrayListOf(
                productIdX1, productIdX2, productIdX3,
                subscriptionIdX1, subscriptionIdX2, subscriptionIdX3,
                subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3
            )
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun initMVSideFrames() {
        binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
        binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val dp5 = (5 * resources.displayMetrics.density).toInt()
            binding.mVerticalSideFrameBottom.layoutParams =
                binding.mVerticalSideFrameBottom.layoutParams.apply { height = navHeight + dp5 }
            insets
        }
    }

    private fun initBouncy() {
        binding.settingsMenu.post {
            // totalScrollRange is used by bouncy/offset logic if needed
        }
    }

    private fun initBouncyListener() {
        binding.settingsMenu.setupOffsetListener { verticalOffset, height ->
            val h = if (height > 0) height else 1
            binding.settingsMenu.titleView?.scaleX = (1 + 0.7f * verticalOffset / h)
            binding.settingsMenu.titleView?.scaleY = (1 + 0.7f * verticalOffset / h)
        }
    }

    private fun setupSettingsTopAppBar() {
        val topBarColor = getRequiredTopBarColor()
        binding.settingsMenu.setTitle(getString(com.goodwy.commons.R.string.settings))
        binding.settingsMenu.toolbar?.let { toolbar ->
            toolbar.navigationIcon =
                resources.getColoredDrawableWithColor(
                    this,
                    com.android.common.R.drawable.ic_cmn_arrow_left_fill,
                    topBarColor.getContrastColor()
                )
            toolbar.setNavigationContentDescription(NavigationIcon.Arrow.accessibilityResId)
            toolbar.setNavigationOnClickListener {
                hideKeyboard()
                finish()
            }
        }
        updateTopBarColors(
            binding.settingsMenu,
            topBarColor,
            binding.settingsMenu.toolbar,
            setAppBarViewBackground = false
        )
        binding.settingsMenu.searchBeVisibleIf(false)
        // Keep collapsed title clear of the back button hit area.
        binding.settingsMenu.titleView?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = (64 * resources.displayMetrics.density).toInt()
        }
    }

    override fun onResume() {
        super.onResume()
        stopCurrentlyPlayingRingtone()
        setupSettingsTopAppBar()

        setupCustomizeColors()
        // Hide "Customize colors" option
        binding.settingsCustomizeColorsHolder.beGone()
        setupOverflowIcon()
        setupFloatingButtonStyle()
        setupUseColoredContacts()
        setupContactsColorList()
        setupColorSimIcons()
        setupSimCardColorList()

        setupManageBlockedNumbers()
        setupManageBlockedKeywords()
        setupManageQuickTexts()
        setupUseSpeechToText()
        setupFontSize()
        setupChangeDateTimeFormat()
        setupShowPhoneNumber()
        setupUseEnglish()

        setupUseSwipeToAction()
        setupSwipeVibration()
        setupSwipeRipple()
        setupSwipeRightAction()
        setupSwipeLeftAction()
        setupArchiveConfirmation()
        setupDeleteConfirmation()

        setupCustomizeNotifications()
        setupNotificationSound()
        setupLockScreenVisibility()
        setupCopyNumberAndDelete()
        setupNotifyTurnsOnScreen()

        setupThreadTopStyle()
        setupMessageBubble()
        setupTextAlignmentMessage()
        setupFontSizeMessage()
        setupActionOnMessageClick()

        setupSendOnEnter()
        setupSoundOnOutGoingMessages()
        // Hide "Sound on out going messages" option
        binding.settingsSoundOnOutGoingMessagesHolder.beGone()
        setupShowSimSelectionDialog()
        setupEnableDeliveryReports()
        setupDeliveryReportSound()
        setupShowCharacterCounter()
        setupMessageSendDelay()
        setupUseSimpleCharacters()

        setupShowDividers()
        setupShowContactThumbnails()
        setupContactThumbnailsSize()
        setupUseRelativeDate()
        setupUnreadAtTop()
        setupLinesCount()
        setupUnreadIndicatorPosition()
        setupHideTopBarWhenScroll()
        setupChangeColourTopBarWhenScroll()
        // Hide "Change top bar colour when scrolling" option
        binding.settingsChangeColourTopBarHolder.beGone()

        setupKeepConversationsArchived()
        // Hide "Keep conversation archived" option
        binding.settingsKeepConversationsArchivedHolder.beGone()

        setupUseRecycleBin()
        // Hide "Move items into the Recycle Bin instead of deleting" option
        binding.settingsUseRecycleBinHolder.beGone()
        setupEmptyRecycleBin()

        setupAppPasswordProtection()
        // Hide "Password protect the whole application" option
        binding.settingsAppPasswordProtectionHolder.beGone()

        setupMessagesExport()
        setupMessagesImport()

        setupTipJar()
        setupAbout()
        updateTextColors(binding.settingsNestedScrollview)
        
        // Hide "Other" section
        binding.settingsOtherLabel.beGone()
        binding.settingsOtherHolder.beGone()
        
        // Hide MMS-related items
        binding.settingsSendLongMessageMmsHolder.beGone()
        binding.settingsSendGroupMessageMmsHolder.beGone()
        binding.settingsMmsFileSizeLimitHolder.beGone()

        if (blockedNumbersAtPause != -1 && blockedNumbersAtPause != getBlockedNumbers().hashCode()) {
            refreshConversations()
        }

        binding.apply {
            val properPrimaryColor = getProperPrimaryColor()
            arrayOf(
                settingsAppearanceLabel,
                settingsGeneralLabel,
                settingsNotificationsLabel,
                settingsMessagesLabel,
                settingsOutgoingMessagesLabel,
                settingsListViewLabel,
                settingsSwipeGesturesLabel,
                settingsArchivedMessagesLabel,
                settingsRecycleBinLabel,
                settingsSecurityLabel,
                settingsBackupsLabel
            ).forEach {
                it.setTextColor(properPrimaryColor)
            }

            val surfaceColor = getSurfaceColor()
            arrayOf(
                settingsColorCustomizationHolder,
                settingsGeneralHolder,
                settingsNotificationsHolder,
                settingsMessagesHolder,
                settingsOutgoingMessagesHolder,
                settingsListViewHolder,
                settingsSwipeGesturesHolder,
                settingsRecycleBinHolder,
                settingsArchivedMessagesHolder,
                settingsSecurityHolder,
                settingsBackupsHolder
            ).forEach {
                it.setCardBackgroundColor(surfaceColor)
            }

            val properTextColor = getProperTextColor()
            arrayOf(
                settingsCustomizeColorsChevron,
                settingsManageBlockedNumbersChevron,
                settingsManageBlockedKeywordsChevron,
                settingsManageQuickTextsChevron,
                settingsCustomizeNotificationsChevron,
                settingsImportMessagesChevron,
                settingsExportMessagesChevron,
                settingsTipJarChevron,
                settingsAboutChevron
            ).forEach {
                it.applyColorFilter(properTextColor)
            }

            settingsMenu.toolbar?.menu?.let { updateMenuItemColors(it) }
        }
    }

    private fun setupMessagesExport() {
        binding.settingsExportMessagesHolder.setOnClickListener {
            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            exportMessagesDialog = ExportMessagesDialog(this, blurTarget) { fileName ->
                saveDocument.launch("$fileName.json")
            }
        }
    }

    private fun setupMessagesImport() {
        binding.settingsImportMessagesHolder.setOnClickListener {
            getContent.launch(messageImportFileTypes.toTypedArray())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
            when (requestCode) {
                PICK_NOTIFICATION_SOUND_INTENT_ID -> {
                    val alarmSound = storeNewYourAlarmSound(resultData)
                    config.notificationSound = alarmSound.uri
                    updateNotificationSoundDisplay()
                    // Update system notification sound
                    val uri = if (alarmSound.uri.isNotEmpty() && alarmSound.uri != SILENT) {
                        android.net.Uri.parse(alarmSound.uri)
                    } else {
                        null
                    }
                    updateSystemNotificationSound(uri)
                }
                PICK_DELIVERY_REPORT_SOUND_INTENT_ID -> {
                    val alarmSound = storeNewYourAlarmSound(resultData)
                    config.deliveryReportSound = alarmSound.uri
                    updateDeliveryReportSoundDisplay()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopCurrentlyPlayingRingtone()
        blockedNumbersAtPause = getBlockedNumbers().hashCode()
    }

    private fun stopCurrentlyPlayingRingtone() {
        try {
            currentlyPlayingRingtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
            currentlyPlayingRingtone = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupCustomizeColors() = binding.apply {
        settingsCustomizeColorsHolder.setOnClickListener {
            startCustomizationActivity(
                showAccentColor = true,
                productIdList = arrayListOf(productIdX1, productIdX2, productIdX3),
                productIdListRu = arrayListOf(productIdX1, productIdX2, productIdX3),
                subscriptionIdList = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
                subscriptionIdListRu = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
                subscriptionYearIdList = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
                subscriptionYearIdListRu = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
                showAppIconColor = true
            )
        }
    }

    private fun setupCustomizeNotifications() = binding.apply {
        if (settingsCustomizeNotificationsHolder.isGone()) {
            settingsLockScreenVisibilityHolder.background =
                AppCompatResources.getDrawable(this@SettingsActivity, R.drawable.ripple_all_corners)
        }

        settingsCustomizeNotificationsHolder.setOnClickListener {
            launchCustomizeNotificationsIntent()
        }
    }

    private fun setupNotificationSound() = binding.apply {
        updateNotificationSoundDisplay()
        settingsNotificationSoundHolder.setOnClickListener {
            hideKeyboard()
            val ringtonePickerIntent = getNotificationSoundPickerIntent()
            try {
                notificationSoundPicker.launch(ringtonePickerIntent)
            } catch (e: Exception) {
                val currentRingtone = config.notificationSound ?: getDefaultAlarmSound(RingtoneManager.TYPE_NOTIFICATION).uri
                val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                SelectAlarmSoundDialog(
                    this@SettingsActivity,
                    currentRingtone,
                    AudioManager.STREAM_NOTIFICATION,
                    PICK_NOTIFICATION_SOUND_INTENT_ID,
                    RingtoneManager.TYPE_NOTIFICATION,
                    false,
                    onAlarmPicked = { alarmSound ->
                        config.notificationSound = alarmSound?.uri
                        updateNotificationSoundDisplay()
                        // Update system notification sound
                        val uri = if (alarmSound?.uri?.isNotEmpty() == true && alarmSound.uri != SILENT) {
                            android.net.Uri.parse(alarmSound.uri)
                        } else {
                            null
                        }
                        updateSystemNotificationSound(uri)
                    },
                    onAlarmSoundDeleted = { alarmSound ->
                        if (config.notificationSound == alarmSound.uri) {
                            val default = getDefaultAlarmSound(RingtoneManager.TYPE_NOTIFICATION)
                            config.notificationSound = default.uri
                            updateNotificationSoundDisplay()
                            // Update system notification sound to default
                            val uri = if (default.uri.isNotEmpty() && default.uri != SILENT) {
                                android.net.Uri.parse(default.uri)
                            } else {
                                null
                            }
                            updateSystemNotificationSound(uri)
                        }
                    }
                )
            }
        }
    }

    private fun getNotificationSoundPickerIntent(): Intent {
        val defaultRingtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val currentRingtoneUri = config.notificationSound?.let { android.net.Uri.parse(it) }
            ?: defaultRingtoneUri

        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.notification_sound))
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultRingtoneUri)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentRingtoneUri)
        }
    }

    companion object {
        private const val PICK_NOTIFICATION_SOUND_INTENT_ID = 1001
        private const val PICK_DELIVERY_REPORT_SOUND_INTENT_ID = 1002
    }

    private fun updateSystemNotificationSound(uri: android.net.Uri?) {
        try {
            // Check if we have permission to modify system settings
            if (Settings.System.canWrite(this)) {
                RingtoneManager.setActualDefaultRingtoneUri(
                    this,
                    RingtoneManager.TYPE_NOTIFICATION,
                    uri
                )
            }
            // Silently skip if permission not granted - this is an optional feature
        } catch (e: Exception) {
            // Log error but don't show to user as this is optional
            e.printStackTrace()
        }
    }

    private fun updateNotificationSoundDisplay() {
        val soundUriString = config.notificationSound
        val soundName = when {
            soundUriString == null -> {
                val default = getDefaultAlarmSound(RingtoneManager.TYPE_NOTIFICATION)
                default.title
            }
            soundUriString == SILENT -> getString(com.goodwy.commons.R.string.no_sound)
            soundUriString.isEmpty() -> getString(com.goodwy.commons.R.string.no_sound)
            else -> {
                try {
                    val uri = android.net.Uri.parse(soundUriString)
                    RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: getString(com.goodwy.commons.R.string.none)
                } catch (e: Exception) {
                    getString(com.goodwy.commons.R.string.none)
                }
            }
        }
        binding.settingsNotificationSoundValue.text = soundName
    }

    private fun setupOverflowIcon() {
        binding.apply {
            settingsOverflowIcon.applyColorFilter(getProperTextColor())
            settingsOverflowIcon.setImageResource(getOverflowIcon(baseConfig.overflowIcon))
            settingsOverflowIconHolder.setOnClickListener {
                val items = arrayListOf(
                    com.goodwy.commons.R.drawable.ic_more_horiz,
                    com.goodwy.commons.R.drawable.ic_three_dots_vector,
                    com.goodwy.commons.R.drawable.ic_more_horiz_round
                )

                val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                IconListDialog(
                    activity = this@SettingsActivity,
                    items = items,
                    checkedItemId = baseConfig.overflowIcon + 1,
                    defaultItemId = OVERFLOW_ICON_HORIZONTAL + 1,
                    titleId = com.goodwy.strings.R.string.overflow_icon,
                    size = pixels(com.goodwy.commons.R.dimen.normal_icon_size).toInt(),
                    color = getProperTextColor(),
                    blurTarget = blurTarget
                ) { wasPositivePressed, newValue ->
                    if (wasPositivePressed) {
                        if (baseConfig.overflowIcon != newValue - 1) {
                            baseConfig.overflowIcon = newValue - 1
                            settingsOverflowIcon.setImageResource(getOverflowIcon(baseConfig.overflowIcon))
                        }
                    }
                }
            }
        }
    }

    private fun setupFloatingButtonStyle() {
        binding.apply {
            settingsFloatingButtonStyle.applyColorFilter(getProperTextColor())
            settingsFloatingButtonStyle.setImageResource(
                if (baseConfig.materialDesign3) com.goodwy.commons.R.drawable.squircle_bg else com.goodwy.commons.R.drawable.ic_circle_filled
            )
            settingsFloatingButtonStyleHolder.setOnClickListener {
                val items = arrayListOf(
                    com.goodwy.commons.R.drawable.ic_circle_filled,
                    com.goodwy.commons.R.drawable.squircle_bg
                )
                val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")

                IconListDialog(
                    activity = this@SettingsActivity,
                    items = items,
                    checkedItemId = if (baseConfig.materialDesign3) 2 else 1,
                    defaultItemId = 1,
                    titleId = com.goodwy.strings.R.string.floating_button_style,
                    size = pixels(com.goodwy.commons.R.dimen.normal_icon_size).toInt(),
                    color = getProperTextColor(),
                    blurTarget = blurTarget
                ) { wasPositivePressed, newValue ->
                    if (wasPositivePressed) {
                        if (newValue != if (baseConfig.materialDesign3) 2 else 1) {
                            baseConfig.materialDesign3 = newValue == 2
                            settingsFloatingButtonStyle.setImageResource(
                                if (newValue == 2) com.goodwy.commons.R.drawable.squircle_bg
                                else com.goodwy.commons.R.drawable.ic_circle_filled
                            )
                            config.needRestart = true
                        }
                    }
                }
            }
        }
    }

    private fun setupThreadTopStyle() = binding.apply {
        settingsThreadTopStyle.text = getThreadTopStyleText()
        settingsThreadTopStyleHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(THREAD_TOP_COMPACT, getString(com.goodwy.commons.R.string.small)),
                RadioItem(THREAD_TOP_LARGE, getString(com.goodwy.commons.R.string.large))
            )

            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupDialog(this@SettingsActivity, items, config.threadTopStyle, R.string.chat_title_style_g, blurTarget = blurTarget) {
                config.threadTopStyle = it as Int
                settingsThreadTopStyle.text = getThreadTopStyleText()
            }
        }
    }

    private fun getThreadTopStyleText() = getString(
        when (config.threadTopStyle) {
            THREAD_TOP_COMPACT -> com.goodwy.commons.R.string.small
            THREAD_TOP_LARGE -> com.goodwy.commons.R.string.large
            else -> com.goodwy.commons.R.string.large
        }
    )

    private fun setupMessageBubble() = binding.apply {
        settingsMessageBubbleIcon.text = getString(R.string.message_bubble_type, config.bubbleDrawableSet)
        settingsMessageBubbleHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, MessageBubblePickerActivity::class.java))
        }
    }

    private fun setupTextAlignmentMessage() = binding.apply {
        settingsTextAlignmentMessage.text = getTextAlignmentMessageText()
        settingsTextAlignmentMessageHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(TEXT_ALIGNMENT_START, getString(com.goodwy.strings.R.string.start)),
                RadioItem(TEXT_ALIGNMENT_ALONG_EDGES, getString(com.goodwy.strings.R.string.text_alignment_along_edges))
            )

            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupDialog(this@SettingsActivity, items, config.textAlignment, com.goodwy.strings.R.string.text_alignment, blurTarget = blurTarget) {
                config.textAlignment = it as Int
                settingsTextAlignmentMessage.text = getTextAlignmentMessageText()
            }
        }
    }

    private fun getTextAlignmentMessageText() = getString(
        when (config.textAlignment) {
            TEXT_ALIGNMENT_START -> com.goodwy.strings.R.string.start
            else -> com.goodwy.strings.R.string.text_alignment_along_edges
        }
    )

    private fun setupFontSizeMessage() = binding.apply {
        settingsFontSizeMessage.text = getFontSizeMessageText()
        settingsFontSizeMessageHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(FONT_SIZE_SMALL, getString(com.goodwy.commons.R.string.small)),
                RadioItem(FONT_SIZE_MEDIUM, getString(com.goodwy.commons.R.string.medium)),
                RadioItem(FONT_SIZE_LARGE, getString(com.goodwy.commons.R.string.large)),
                RadioItem(FONT_SIZE_EXTRA_LARGE, getString(com.goodwy.commons.R.string.extra_large))
            )

            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupDialog(this@SettingsActivity, items, config.fontSizeMessage, com.goodwy.commons.R.string.font_size, blurTarget = blurTarget) {
                config.fontSizeMessage = it as Int
                settingsFontSizeMessage.text = getFontSizeMessageText()
            }
        }
    }

    private fun getFontSizeMessageText() = getString(
        when (config.fontSizeMessage) {
            FONT_SIZE_SMALL -> com.goodwy.commons.R.string.small
            FONT_SIZE_MEDIUM -> com.goodwy.commons.R.string.medium
            FONT_SIZE_LARGE -> com.goodwy.commons.R.string.large
            else -> com.goodwy.commons.R.string.extra_large
        }
    )

    private fun setupUseEnglish() = binding.apply {
        settingsUseEnglishHolder.beVisibleIf(
            (config.wasUseEnglishToggled || Locale.getDefault().language != "en") && !isTiramisuPlus()
        )
        settingsUseEnglish.isChecked = config.useEnglish
        settingsUseEnglish.setOnCheckedChangeListener { isChecked ->
            // Only exit if the value actually changed
            if (config.useEnglish != isChecked) {
                config.useEnglish = isChecked
                exitProcess(0)
            }
        }
        settingsUseEnglishHolder.setOnClickListener {
            settingsUseEnglish.toggle()
        }
    }

    private fun setupManageBlockedNumbers() = binding.apply {
        @SuppressLint("SetTextI18n")
        settingsManageBlockedNumbersCount.text = getBlockedNumbers().size.toString()

        val getProperTextColor = getProperTextColor()
        val red = resources.getColor(com.goodwy.commons.R.color.red_missed, theme)
        val colorUnknown = if (baseConfig.blockUnknownNumbers) red else getProperTextColor
        val alphaUnknown = if (baseConfig.blockUnknownNumbers) 1f else 0.6f
        settingsManageBlockedNumbersIconUnknown.apply {
            applyColorFilter(colorUnknown)
            alpha = alphaUnknown
        }

        settingsManageBlockedNumbersHolder.setOnClickListener {
            Intent(this@SettingsActivity, ManageBlockedNumbersActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupManageBlockedKeywords() = binding.apply {
        @SuppressLint("SetTextI18n")
        settingsManageBlockedKeywordsCount.text = config.blockedKeywords.size.toString()
        settingsManageBlockedKeywordsHolder.setOnClickListener {
            Intent(this@SettingsActivity, ManageBlockedKeywordsActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupManageQuickTexts() = binding.apply {
        @SuppressLint("SetTextI18n")
        settingsManageQuickTextsCount.text = config.quickTexts.size.toString()
        settingsManageQuickTextsHolder.setOnClickListener {
            Intent(this@SettingsActivity, ManageQuickTextsActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupUseSpeechToText() = binding.apply {
        settingsUseSpeechToText.isChecked = config.useSpeechToText
        settingsUseSpeechToText.setOnCheckedChangeListener { isChecked ->
            config.useSpeechToText = isChecked
            config.needRestart = true
        }
        settingsUseSpeechToTextHolder.setOnClickListener {
            settingsUseSpeechToText.toggle()
        }
    }

    private fun setupChangeDateTimeFormat() = binding.apply {
        updateDateTimeFormat()
        settingsChangeDateTimeFormatHolder.setOnClickListener {
            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            ChangeDateTimeFormatDialog(this@SettingsActivity, true) {
                updateDateTimeFormat()
                refreshConversations()
//                config.needRestart = true
            }
        }
    }

    private fun updateDateTimeFormat() {
        val cal = Calendar.getInstance(Locale.ENGLISH).timeInMillis
        val formatDate = cal.formatDate(this@SettingsActivity)
        binding.settingsChangeDateTimeFormat.text = formatDate
    }

    private fun setupShowPhoneNumber() = binding.apply {
        settingsShowPhoneNumber.isChecked = config.showPhoneNumber
        settingsShowPhoneNumber.setOnCheckedChangeListener { isChecked ->
            config.showPhoneNumber = isChecked
            config.needRestart = true
        }
        settingsShowPhoneNumberHolder.setOnClickListener {
            settingsShowPhoneNumber.toggle()
        }
    }

    private fun setupFontSize() = binding.apply {
        settingsFontSize.text = getFontSizeText()
        settingsFontSizeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(FONT_SIZE_SMALL, getString(com.goodwy.commons.R.string.small)),
                RadioItem(FONT_SIZE_MEDIUM, getString(com.goodwy.commons.R.string.medium)),
                RadioItem(FONT_SIZE_LARGE, getString(com.goodwy.commons.R.string.large)),
                RadioItem(FONT_SIZE_EXTRA_LARGE, getString(com.goodwy.commons.R.string.extra_large))
            )

            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupDialog(this@SettingsActivity, items, config.fontSize, com.goodwy.commons.R.string.font_size, blurTarget = blurTarget) {
                config.fontSize = it as Int
                settingsFontSize.text = getFontSizeText()
            }
        }
    }

    private fun setupShowCharacterCounter() = binding.apply {
        settingsShowCharacterCounter.isChecked = config.showCharacterCounter
        settingsShowCharacterCounter.setOnCheckedChangeListener { isChecked ->
            config.showCharacterCounter = isChecked
        }
        settingsShowCharacterCounterHolder.setOnClickListener {
            settingsShowCharacterCounter.toggle()
        }
    }

    private fun setupMessageSendDelay() = binding.apply {
        settingsMessageSendDelay.text = getMessageSendDelayText()
        settingsMessageSendDelayHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(0, getString(R.string.no_delay)),
                RadioItem(3, getString(R.string.delay_3s)),
                RadioItem(5, getString(R.string.delay_5s)),
                RadioItem(10, getString(R.string.delay_10s))
            )

            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupDialog(this@SettingsActivity, items, config.messageSendDelay, R.string.message_send_delay, blurTarget = blurTarget) {
                config.messageSendDelay = it as Int
                settingsMessageSendDelay.text = getMessageSendDelayText()
            }
        }
    }

    private fun getMessageSendDelayText() = getString(
        when (config.messageSendDelay) {
            0 -> R.string.no_delay
            3 -> R.string.delay_3s
            5 -> R.string.delay_5s
            10 -> R.string.delay_10s
            else -> R.string.no_delay
        }
    )

    @SuppressLint("PrivateResource")
    private fun setupActionOnMessageClick() = binding.apply {
        settingsActionOnMessageClick.text = getActionOnMessageClickText()
        settingsActionOnMessageClickHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(ACTION_COPY_MESSAGE, getString(com.goodwy.commons.R.string.copy_to_clipboard), icon = com.goodwy.commons.R.drawable.ic_copy_vector),
                RadioItem(ACTION_SELECT_TEXT, getString(com.goodwy.commons.R.string.select_text), icon = R.drawable.ic_text_select),
                RadioItem(ACTION_NOTHING, getString(R.string.exposed_dropdown_menu), icon = R.drawable.ic_menu_open),
            )

            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupIconDialog(this@SettingsActivity, items, config.actionOnMessageClickSetting, com.goodwy.strings.R.string.action_on_message_click, blurTarget = blurTarget) {
                config.actionOnMessageClickSetting = it as Int
                settingsActionOnMessageClick.text = getActionOnMessageClickText()
            }
        }
    }

    @SuppressLint("PrivateResource")
    private fun getActionOnMessageClickText() = getString(
        when (config.actionOnMessageClickSetting) {
            ACTION_COPY_CODE -> com.goodwy.strings.R.string.copy_code
            ACTION_COPY_MESSAGE -> com.goodwy.commons.R.string.copy_to_clipboard
            ACTION_SELECT_TEXT -> com.goodwy.commons.R.string.select_text
            else -> R.string.exposed_dropdown_menu
        }
    )

    private fun setupUseSimpleCharacters() = binding.apply {
        settingsUseSimpleCharacters.isChecked = config.useSimpleCharacters
        settingsUseSimpleCharacters.setOnCheckedChangeListener { isChecked ->
            config.useSimpleCharacters = isChecked
        }
        settingsUseSimpleCharactersHolder.setOnClickListener {
            settingsUseSimpleCharacters.toggle()
        }
    }

    private fun setupSendOnEnter() = binding.apply {
        settingsSendOnEnter.isChecked = config.sendOnEnter
        settingsSendOnEnter.setOnCheckedChangeListener { isChecked ->
            config.sendOnEnter = isChecked
        }
        settingsSendOnEnterHolder.setOnClickListener {
            settingsSendOnEnter.toggle()
        }
    }

    private fun setupSoundOnOutGoingMessages() = binding.apply {
        settingsSoundOnOutGoingMessages.isChecked = config.soundOnOutGoingMessages
        settingsSoundOnOutGoingMessages.setOnCheckedChangeListener { isChecked ->
            config.soundOnOutGoingMessages = isChecked
        }
        settingsSoundOnOutGoingMessagesHolder.setOnClickListener {
            settingsSoundOnOutGoingMessages.toggle()
        }
    }

    private fun setupShowSimSelectionDialog() = binding.apply {
        settingsShowSimSelectionDialogHolder.beVisibleIf(areMultipleSIMsAvailable())
        settingsShowSimSelectionDialog.isChecked = config.showSimSelectionDialog
        settingsShowSimSelectionDialog.setOnCheckedChangeListener { isChecked ->
            config.showSimSelectionDialog = isChecked
        }
        settingsShowSimSelectionDialogHolder.setOnClickListener {
            settingsShowSimSelectionDialog.toggle()
        }
    }

    private fun setupEnableDeliveryReports() = binding.apply {
        settingsEnableDeliveryReports.isChecked = config.enableDeliveryReports
        settingsEnableDeliveryReports.setOnCheckedChangeListener { isChecked ->
            config.enableDeliveryReports = isChecked
            updateDeliveryReportSoundVisibility()
        }
        settingsEnableDeliveryReportsHolder.setOnClickListener {
            settingsEnableDeliveryReports.toggle()
        }
        updateDeliveryReportSoundVisibility()
    }

    private fun updateDeliveryReportSoundVisibility() {
        binding.settingsDeliveryReportSoundHolder.beVisibleIf(config.enableDeliveryReports)
    }

    private fun setupDeliveryReportSound() = binding.apply {
        updateDeliveryReportSoundVisibility()
        updateDeliveryReportSoundDisplay()
        settingsDeliveryReportSoundHolder.setOnClickListener {
            hideKeyboard()
            val ringtonePickerIntent = getDeliveryReportSoundPickerIntent()
            try {
                deliveryReportSoundPicker.launch(ringtonePickerIntent)
            } catch (e: Exception) {
                val currentRingtone = config.deliveryReportSound ?: getDefaultAlarmSound(RingtoneManager.TYPE_NOTIFICATION).uri
                val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                SelectAlarmSoundDialog(
                    this@SettingsActivity,
                    currentRingtone,
                    AudioManager.STREAM_NOTIFICATION,
                    PICK_DELIVERY_REPORT_SOUND_INTENT_ID,
                    RingtoneManager.TYPE_NOTIFICATION,
                    false,
                    onAlarmPicked = { alarmSound ->
                        config.deliveryReportSound = alarmSound?.uri
                        updateDeliveryReportSoundDisplay()
                    },
                    onAlarmSoundDeleted = { alarmSound ->
                        if (config.deliveryReportSound == alarmSound.uri) {
                            val default = getDefaultAlarmSound(RingtoneManager.TYPE_NOTIFICATION)
                            config.deliveryReportSound = default.uri
                            updateDeliveryReportSoundDisplay()
                        }
                    }
                )
            }
        }
    }

    private fun getDeliveryReportSoundPickerIntent(): Intent {
        val defaultRingtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val currentRingtoneUri = config.deliveryReportSound?.let { android.net.Uri.parse(it) }
            ?: defaultRingtoneUri

        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.delivery_report_sound))
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultRingtoneUri)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentRingtoneUri)
        }
    }

    private fun updateDeliveryReportSoundDisplay() {
        val soundUriString = config.deliveryReportSound
        val soundName = when {
            soundUriString == null -> {
                val default = getDefaultAlarmSound(RingtoneManager.TYPE_NOTIFICATION)
                default.title
            }
            soundUriString == SILENT -> getString(com.goodwy.commons.R.string.no_sound)
            soundUriString.isEmpty() -> getString(com.goodwy.commons.R.string.no_sound)
            else -> {
                try {
                    val uri = android.net.Uri.parse(soundUriString)
                    RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: getString(com.goodwy.commons.R.string.none)
                } catch (e: Exception) {
                    getString(com.goodwy.commons.R.string.none)
                }
            }
        }
        binding.settingsDeliveryReportSoundValue.text = soundName
    }

    private fun setupKeepConversationsArchived() = binding.apply {
        settingsKeepConversationsArchivedHolder.beVisibleIf(config.isArchiveAvailable)
        settingsKeepConversationsArchived.isChecked = config.keepConversationsArchived
        settingsKeepConversationsArchived.setOnCheckedChangeListener { isChecked ->
            config.keepConversationsArchived = isChecked
        }
        settingsKeepConversationsArchivedHolder.setOnClickListener {
            settingsKeepConversationsArchived.toggle()
        }
    }

    private fun setupLockScreenVisibility() = binding.apply {
        settingsLockScreenVisibility.text = getLockScreenVisibilityText()
        settingsLockScreenVisibilityHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(LOCK_SCREEN_SENDER_MESSAGE, getString(R.string.sender_and_message)),
                RadioItem(LOCK_SCREEN_SENDER, getString(R.string.sender_only)),
                RadioItem(LOCK_SCREEN_NOTHING, getString(com.goodwy.commons.R.string.nothing)),
            )

            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupDialog(this@SettingsActivity, items, config.lockScreenVisibilitySetting, R.string.lock_screen_visibility, blurTarget = blurTarget) {
                config.lockScreenVisibilitySetting = it as Int
                settingsLockScreenVisibility.text = getLockScreenVisibilityText()
            }
        }
    }

    private fun setupCopyNumberAndDelete() = binding.apply {
        settingsCopyNumberAndDelete.isChecked = config.copyNumberAndDelete
        settingsCopyNumberAndDelete.setOnCheckedChangeListener { isChecked ->
            config.copyNumberAndDelete = isChecked
        }
        settingsCopyNumberAndDeleteHolder.setOnClickListener {
            settingsCopyNumberAndDelete.toggle()
        }
    }

    private fun setupNotifyTurnsOnScreen() = binding.apply {
        settingsNotifyTurnsOnScreen.isChecked = config.notifyTurnsOnScreen
        settingsNotifyTurnsOnScreen.setOnCheckedChangeListener { isChecked ->
            config.notifyTurnsOnScreen = isChecked
        }
        settingsNotifyTurnsOnScreenHolder.setOnClickListener {
            settingsNotifyTurnsOnScreen.toggle()
        }
    }

    private fun getLockScreenVisibilityText() = getString(
        when (config.lockScreenVisibilitySetting) {
            LOCK_SCREEN_SENDER_MESSAGE -> R.string.sender_and_message
            LOCK_SCREEN_SENDER -> R.string.sender_only
            else -> com.goodwy.commons.R.string.nothing
        }
    )

    private fun setupUseSwipeToAction() {
        updateSwipeToActionVisible()
        binding.apply {
            settingsUseSwipeToAction.isChecked = config.useSwipeToAction
            settingsUseSwipeToAction.setOnCheckedChangeListener { isChecked ->
                config.useSwipeToAction = isChecked
                config.needRestart = true
                updateSwipeToActionVisible()
            }
            settingsUseSwipeToActionHolder.setOnClickListener {
                settingsUseSwipeToAction.toggle()
            }
        }
    }

    private fun updateSwipeToActionVisible() {
        binding.apply {
            settingsSwipeVibrationHolder.beVisibleIf(config.useSwipeToAction)
            settingsSwipeRippleHolder.beVisibleIf(config.useSwipeToAction)
            settingsSwipeRightActionHolder.beVisibleIf(config.useSwipeToAction)
            settingsSwipeLeftActionHolder.beVisibleIf(config.useSwipeToAction)
            settingsSkipArchiveConfirmationHolder.beVisibleIf(
                (config.swipeLeftAction == SWIPE_ACTION_ARCHIVE || config.swipeRightAction == SWIPE_ACTION_ARCHIVE)
                    && config.isArchiveAvailable && config.useSwipeToAction
                )
            settingsSkipDeleteConfirmationHolder.beVisibleIf(config.useSwipeToAction &&(config.swipeLeftAction == SWIPE_ACTION_DELETE || config.swipeRightAction == SWIPE_ACTION_DELETE))
        }
    }

    private fun setupSwipeVibration() {
        binding.apply {
            settingsSwipeVibration.isChecked = config.swipeVibration
            settingsSwipeVibration.setOnCheckedChangeListener { isChecked ->
                config.swipeVibration = isChecked
                config.needRestart = true
            }
            settingsSwipeVibrationHolder.setOnClickListener {
                settingsSwipeVibration.toggle()
            }
        }
    }

    private fun setupSwipeRipple() {
        binding.apply {
            settingsSwipeRipple.isChecked = config.swipeRipple
            settingsSwipeRipple.setOnCheckedChangeListener { isChecked ->
                config.swipeRipple = isChecked
                config.needRestart = true
            }
            settingsSwipeRippleHolder.setOnClickListener {
                settingsSwipeRipple.toggle()
            }
        }
    }

    private fun setupSwipeRightAction() = binding.apply {
        if (isRTLLayout) settingsSwipeRightActionLabel.text = getString(com.goodwy.strings.R.string.swipe_left_action)
        settingsSwipeRightAction.text = getSwipeActionText(false)
        settingsSwipeRightActionHolder.setOnClickListener {
            val items = if (config.isArchiveAvailable) arrayListOf(
                RadioItem(SWIPE_ACTION_MARK_READ, getString(R.string.mark_as_read), icon = R.drawable.ic_mark_read),
                RadioItem(SWIPE_ACTION_DELETE, getString(com.goodwy.commons.R.string.delete), icon = com.goodwy.commons.R.drawable.ic_delete_outline),
                RadioItem(SWIPE_ACTION_ARCHIVE, getString(R.string.archive), icon = R.drawable.ic_archive_vector),
                RadioItem(SWIPE_ACTION_BLOCK, getString(com.goodwy.commons.R.string.block_number), icon = com.goodwy.commons.R.drawable.ic_block_vector),
                RadioItem(SWIPE_ACTION_CALL, getString(com.goodwy.commons.R.string.call), icon = com.goodwy.commons.R.drawable.ic_phone_vector),
                RadioItem(SWIPE_ACTION_MESSAGE, getString(com.goodwy.commons.R.string.send_sms), icon = R.drawable.ic_messages),
                RadioItem(SWIPE_ACTION_NONE, getString(com.goodwy.commons.R.string.nothing)),
            ) else arrayListOf(
                RadioItem(SWIPE_ACTION_MARK_READ, getString(R.string.mark_as_read), icon = R.drawable.ic_mark_read),
                RadioItem(SWIPE_ACTION_DELETE, getString(com.goodwy.commons.R.string.delete), icon = com.goodwy.commons.R.drawable.ic_delete_outline),
                RadioItem(SWIPE_ACTION_BLOCK, getString(com.goodwy.commons.R.string.block_number), icon = com.goodwy.commons.R.drawable.ic_block_vector),
                RadioItem(SWIPE_ACTION_CALL, getString(com.goodwy.commons.R.string.call), icon = com.goodwy.commons.R.drawable.ic_phone_vector),
                RadioItem(SWIPE_ACTION_MESSAGE, getString(com.goodwy.commons.R.string.send_sms), icon = R.drawable.ic_messages),
                RadioItem(SWIPE_ACTION_NONE, getString(com.goodwy.commons.R.string.nothing)),
            )

            val title =
                if (isRTLLayout) com.goodwy.strings.R.string.swipe_left_action else com.goodwy.strings.R.string.swipe_right_action
            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupIconDialog(this@SettingsActivity, items, config.swipeRightAction, title, blurTarget = blurTarget) {
                config.swipeRightAction = it as Int
                config.needRestart = true
                settingsSwipeRightAction.text = getSwipeActionText(false)
                settingsSkipArchiveConfirmationHolder.beVisibleIf(
                    (config.swipeLeftAction == SWIPE_ACTION_ARCHIVE || config.swipeRightAction == SWIPE_ACTION_ARCHIVE)
                        && config.isArchiveAvailable
                )
                settingsSkipDeleteConfirmationHolder.beVisibleIf(
                    (config.swipeLeftAction == SWIPE_ACTION_DELETE || config.swipeRightAction == SWIPE_ACTION_DELETE)
                        && config.isArchiveAvailable
                )
            }
        }
    }

    private fun setupSwipeLeftAction() = binding.apply {
        val pro = isPro()
        settingsSwipeLeftActionHolder.alpha = if (pro) 1f else 0.4f
        val stringId =
            if (isRTLLayout) com.goodwy.strings.R.string.swipe_right_action else com.goodwy.strings.R.string.swipe_left_action
        settingsSwipeLeftActionLabel.text = addLockedLabelIfNeeded(stringId, pro)
        settingsSwipeLeftAction.text = getSwipeActionText(true)
        settingsSwipeLeftActionHolder.setOnClickListener {
            if (pro) {
                val items = if (config.isArchiveAvailable) arrayListOf(
                    RadioItem(SWIPE_ACTION_MARK_READ, getString(R.string.mark_as_read), icon = R.drawable.ic_mark_read),
                    RadioItem(SWIPE_ACTION_DELETE, getString(com.goodwy.commons.R.string.delete), icon = com.goodwy.commons.R.drawable.ic_delete_outline),
                    RadioItem(SWIPE_ACTION_ARCHIVE, getString(R.string.archive), icon = R.drawable.ic_archive_vector),
                    RadioItem(SWIPE_ACTION_BLOCK, getString(com.goodwy.commons.R.string.block_number), icon = com.goodwy.commons.R.drawable.ic_block_vector),
                    RadioItem(SWIPE_ACTION_CALL, getString(com.goodwy.commons.R.string.call), icon = com.goodwy.commons.R.drawable.ic_phone_vector),
                    RadioItem(SWIPE_ACTION_MESSAGE, getString(com.goodwy.commons.R.string.send_sms), icon = R.drawable.ic_messages),
                    RadioItem(SWIPE_ACTION_NONE, getString(com.goodwy.commons.R.string.nothing)),
                ) else arrayListOf(
                    RadioItem(SWIPE_ACTION_MARK_READ, getString(R.string.mark_as_read), icon = R.drawable.ic_mark_read),
                    RadioItem(SWIPE_ACTION_DELETE, getString(com.goodwy.commons.R.string.delete), icon = com.goodwy.commons.R.drawable.ic_delete_outline),
                    RadioItem(SWIPE_ACTION_BLOCK, getString(com.goodwy.commons.R.string.block_number), icon = com.goodwy.commons.R.drawable.ic_block_vector),
                    RadioItem(SWIPE_ACTION_CALL, getString(com.goodwy.commons.R.string.call), icon = com.goodwy.commons.R.drawable.ic_phone_vector),
                    RadioItem(SWIPE_ACTION_MESSAGE, getString(com.goodwy.commons.R.string.send_sms), icon = R.drawable.ic_messages),
                    RadioItem(SWIPE_ACTION_NONE, getString(com.goodwy.commons.R.string.nothing)),
                )

                val title =
                    if (isRTLLayout) com.goodwy.strings.R.string.swipe_right_action else com.goodwy.strings.R.string.swipe_left_action
                val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                RadioGroupIconDialog(this@SettingsActivity, items, config.swipeLeftAction, title, blurTarget = blurTarget) {
                    config.swipeLeftAction = it as Int
                    config.needRestart = true
                    settingsSwipeLeftAction.text = getSwipeActionText(true)
                    settingsSkipArchiveConfirmationHolder.beVisibleIf(
                        (config.swipeLeftAction == SWIPE_ACTION_ARCHIVE || config.swipeRightAction == SWIPE_ACTION_ARCHIVE)
                            && config.isArchiveAvailable
                    )
                    settingsSkipDeleteConfirmationHolder.beVisibleIf(
                        (config.swipeLeftAction == SWIPE_ACTION_DELETE || config.swipeRightAction == SWIPE_ACTION_DELETE)
                            && config.isArchiveAvailable
                    )
                }
            } else {
                RxAnimation.from(settingsSwipeLeftActionHolder)
                    .shake(shakeTranslation = 2f)
                    .subscribe()

                showSnackbar(binding.root)
            }
        }
    }

    private fun getSwipeActionText(left: Boolean) = getString(
        when (if (left) config.swipeLeftAction else config.swipeRightAction) {
            SWIPE_ACTION_MARK_READ -> R.string.mark_as_read
            SWIPE_ACTION_DELETE -> com.goodwy.commons.R.string.delete
            SWIPE_ACTION_ARCHIVE -> R.string.archive
            SWIPE_ACTION_BLOCK -> com.goodwy.commons.R.string.block_number
            SWIPE_ACTION_CALL -> com.goodwy.commons.R.string.call
            SWIPE_ACTION_MESSAGE -> com.goodwy.commons.R.string.send_sms
            else -> com.goodwy.commons.R.string.nothing
        }
    )

    private fun setupArchiveConfirmation() {
        binding.apply {
            //settingsSkipArchiveConfirmationHolder.beVisibleIf(config.swipeLeftAction == SWIPE_ACTION_ARCHIVE || config.swipeRightAction == SWIPE_ACTION_ARCHIVE)
            settingsSkipArchiveConfirmation.isChecked = config.skipArchiveConfirmation
            settingsSkipArchiveConfirmation.setOnCheckedChangeListener { isChecked ->
                config.skipArchiveConfirmation = isChecked
            }
            settingsSkipArchiveConfirmationHolder.setOnClickListener {
                settingsSkipArchiveConfirmation.toggle()
            }
        }
    }

    private fun setupDeleteConfirmation() {
        binding.apply {
            //settingsSkipDeleteConfirmationHolder.beVisibleIf(config.swipeLeftAction == SWIPE_ACTION_DELETE || config.swipeRightAction == SWIPE_ACTION_DELETE)
            settingsSkipDeleteConfirmation.isChecked = config.skipDeleteConfirmation
            settingsSkipDeleteConfirmation.setOnCheckedChangeListener { isChecked ->
                config.skipDeleteConfirmation = isChecked
            }
            settingsSkipDeleteConfirmationHolder.setOnClickListener {
                settingsSkipDeleteConfirmation.toggle()
            }
        }
    }

    private fun setupUseRecycleBin() = binding.apply {
        updateRecycleBinButtons()
        settingsUseRecycleBin.isChecked = config.useRecycleBin
        settingsUseRecycleBin.setOnCheckedChangeListener { isChecked ->
            config.useRecycleBin = isChecked
            updateRecycleBinButtons()
        }
        settingsUseRecycleBinHolder.setOnClickListener {
            settingsUseRecycleBin.toggle()
        }
    }

    private fun updateRecycleBinButtons() = binding.apply {
        settingsEmptyRecycleBinHolder.beVisibleIf(config.useRecycleBin)
    }

    private fun setupEmptyRecycleBin() = binding.apply {
        ensureBackgroundThread {
            recycleBinMessages = messagesDB.getArchivedCount()
            runOnUiThread {
                settingsEmptyRecycleBinSize.text =
                    resources.getQuantityString(R.plurals.delete_messages, recycleBinMessages, recycleBinMessages)
            }
        }

        settingsEmptyRecycleBinHolder.setOnClickListener {
            if (recycleBinMessages == 0) {
                toast(com.goodwy.commons.R.string.recycle_bin_empty)
            } else {
                val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                ConfirmationDialog(
                    activity = this@SettingsActivity,
                    message = "",
                    messageId = R.string.empty_recycle_bin_messages_confirmation,
                    positive = com.goodwy.commons.R.string.yes,
                    negative = com.goodwy.commons.R.string.no,
                    blurTarget = blurTarget
                ) {
                    ensureBackgroundThread {
                        emptyMessagesRecycleBin()
                    }
                    recycleBinMessages = 0
                    settingsEmptyRecycleBinSize.text =
                        resources.getQuantityString(R.plurals.delete_messages, recycleBinMessages, recycleBinMessages)
                }
            }
        }
    }

    private fun setupAppPasswordProtection() = binding.apply {
        settingsAppPasswordProtection.isChecked = config.isAppPasswordProtectionOn
        settingsAppPasswordProtection.setOnCheckedChangeListener { isChecked ->
            // Only show dialog if the value actually changed
            if (config.isAppPasswordProtectionOn != isChecked) {
                val tabToShow = if (config.isAppPasswordProtectionOn) config.appProtectionType else SHOW_ALL_TABS

                val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                SecurityDialog(
                    activity = this@SettingsActivity,
                    requiredHash = config.appPasswordHash,
                    showTabIndex = tabToShow,
                    blurTarget = blurTarget
                ) { hash, type, success ->
                    if (success) {
                        val hasPasswordProtection = config.isAppPasswordProtectionOn
                        settingsAppPasswordProtection.isChecked = !hasPasswordProtection
                        config.isAppPasswordProtectionOn = !hasPasswordProtection
                        config.appPasswordHash = if (hasPasswordProtection) "" else hash
                        config.appProtectionType = type

                        if (config.isAppPasswordProtectionOn) {
                            val confirmationTextId =
                                if (config.appProtectionType == PROTECTION_FINGERPRINT) {
                                    com.goodwy.commons.R.string.fingerprint_setup_successfully
                                } else {
                                    com.goodwy.commons.R.string.protection_setup_successfully
                                }

                            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                                ?: throw IllegalStateException("mainBlurTarget not found")
                            ConfirmationDialog(
                                activity = this@SettingsActivity,
                                message = "",
                                messageId = confirmationTextId,
                                positive = com.goodwy.commons.R.string.ok,
                                negative = 0,
                                blurTarget = blurTarget
                            ) { }
                        }
                    } else {
                        // Revert the switch if dialog was cancelled
                        settingsAppPasswordProtection.isChecked = config.isAppPasswordProtectionOn
                    }
                }
            }
        }
        settingsAppPasswordProtectionHolder.setOnClickListener {
            settingsAppPasswordProtection.toggle()
        }
    }

    private fun setupShowDividers() = binding.apply {
        settingsShowDividers.isChecked = config.useDividers
        settingsShowDividers.setOnCheckedChangeListener { isChecked ->
            config.useDividers = isChecked
            config.needRestart = true
        }
        settingsShowDividersHolder.setOnClickListener {
            settingsShowDividers.toggle()
        }
    }

    private fun setupShowContactThumbnails() = binding.apply {
        settingsShowContactThumbnails.isChecked = config.showContactThumbnails
        settingsShowContactThumbnails.setOnCheckedChangeListener { isChecked ->
            config.showContactThumbnails = isChecked
            settingsContactThumbnailsSizeHolder.beVisibleIf(config.showContactThumbnails)
            config.needRestart = true
        }
        settingsShowContactThumbnailsHolder.setOnClickListener {
            settingsShowContactThumbnails.toggle()
        }
    }

    private fun setupContactThumbnailsSize() = binding.apply {
        val pro = isPro()
        settingsContactThumbnailsSizeHolder.beVisibleIf(config.showContactThumbnails)
        settingsContactThumbnailsSizeHolder.alpha = if (pro) 1f else 0.4f
        settingsContactThumbnailsSizeLabel.text = addLockedLabelIfNeeded(com.goodwy.strings.R.string.contact_thumbnails_size, pro)
        settingsContactThumbnailsSize.text = getContactThumbnailsSizeText()
        settingsContactThumbnailsSizeHolder.setOnClickListener {
            if (pro) {
                val items = arrayListOf(
                    RadioItem(FONT_SIZE_SMALL, getString(com.goodwy.commons.R.string.small), CONTACT_THUMBNAILS_SIZE_SMALL),
                    RadioItem(FONT_SIZE_MEDIUM, getString(com.goodwy.commons.R.string.medium), CONTACT_THUMBNAILS_SIZE_MEDIUM),
                    RadioItem(FONT_SIZE_LARGE, getString(com.goodwy.commons.R.string.large), CONTACT_THUMBNAILS_SIZE_LARGE),
                    RadioItem(FONT_SIZE_EXTRA_LARGE, getString(com.goodwy.commons.R.string.extra_large), CONTACT_THUMBNAILS_SIZE_EXTRA_LARGE)
                )

                val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                RadioGroupDialog(this@SettingsActivity, items, config.contactThumbnailsSize, com.goodwy.strings.R.string.contact_thumbnails_size, blurTarget = blurTarget) {
                    config.contactThumbnailsSize = it as Int
                    settingsContactThumbnailsSize.text = getContactThumbnailsSizeText()
                    config.needRestart = true
                }
            } else {
                RxAnimation.from(settingsContactThumbnailsSizeHolder)
                    .shake(shakeTranslation = 2f)
                    .subscribe()

                showSnackbar(binding.root)
            }
        }
    }

    private fun getContactThumbnailsSizeText() = getString(
        when (baseConfig.contactThumbnailsSize) {
            CONTACT_THUMBNAILS_SIZE_SMALL -> com.goodwy.commons.R.string.small
            CONTACT_THUMBNAILS_SIZE_MEDIUM -> com.goodwy.commons.R.string.medium
            CONTACT_THUMBNAILS_SIZE_LARGE -> com.goodwy.commons.R.string.large
            else -> com.goodwy.commons.R.string.extra_large
        }
    )

    private fun setupUseRelativeDate() = binding.apply {
        settingsRelativeDate.isChecked = config.useRelativeDate
        settingsRelativeDate.setOnCheckedChangeListener { isChecked ->
            config.useRelativeDate = isChecked
            config.needRestart = true
        }
        settingsRelativeDateHolder.setOnClickListener {
            settingsRelativeDate.toggle()
        }
    }

    private fun setupUnreadAtTop() = binding.apply {
        settingsUnreadAtTop.isChecked = config.unreadAtTop
        settingsUnreadAtTop.setOnCheckedChangeListener { isChecked ->
            config.unreadAtTop = isChecked
            config.needRestart = true
        }
        settingsUnreadAtTopHolder.setOnClickListener {
            settingsUnreadAtTop.toggle()
        }
    }

    private fun setupLinesCount() = binding.apply {
        @SuppressLint("SetTextI18n")
        settingsLinesCount.text = config.linesCount.toString()
        settingsLinesCountHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(1, "1", icon = R.drawable.ic_lines_count_1),
                RadioItem(2, "2", icon = R.drawable.ic_lines_count_2),
                RadioItem(3, "3", icon = R.drawable.ic_lines_count_3),
                RadioItem(4, "4", icon = R.drawable.ic_lines_count_4)
            )

            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupIconDialog(this@SettingsActivity, items, config.linesCount, com.goodwy.strings.R.string.lines_count, blurTarget = blurTarget) {
                config.linesCount = it as Int
                settingsLinesCount.text = it.toString()
                config.needRestart = true
            }
        }
    }

    private fun setupUnreadIndicatorPosition() = binding.apply {
        settingsUnreadIndicatorPosition.text = getUnreadIndicatorPositionText()
        settingsUnreadIndicatorPositionHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(UNREAD_INDICATOR_START, getString(com.goodwy.strings.R.string.start), icon = R.drawable.ic_unread_start),
                RadioItem(UNREAD_INDICATOR_END, getString(com.goodwy.strings.R.string.end), icon = R.drawable.ic_unread_end)
            )

            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupIconDialog(this@SettingsActivity, items, config.unreadIndicatorPosition, com.goodwy.strings.R.string.unread_indicator_position, blurTarget = blurTarget) {
                config.unreadIndicatorPosition = it as Int
                settingsUnreadIndicatorPosition.text = getUnreadIndicatorPositionText()
                config.needRestart = true
            }
        }
    }

    private fun getUnreadIndicatorPositionText() = getString(
        when (config.unreadIndicatorPosition) {
            UNREAD_INDICATOR_START -> com.goodwy.strings.R.string.start
            else -> com.goodwy.strings.R.string.end
        }
    )

    private fun setupHideTopBarWhenScroll() = binding.apply {
        settingsHideBarWhenScroll.isChecked = config.hideTopBarWhenScroll
        settingsHideBarWhenScroll.setOnCheckedChangeListener { isChecked ->
            config.hideTopBarWhenScroll = isChecked
            config.needRestart = true
        }
        settingsHideBarWhenScrollHolder.setOnClickListener {
            settingsHideBarWhenScroll.toggle()
        }
    }

    private fun setupChangeColourTopBarWhenScroll() = binding.apply {
        settingsChangeColourTopBar.isChecked = config.changeColourTopBar
        settingsChangeColourTopBar.setOnCheckedChangeListener { isChecked ->
            config.changeColourTopBar = isChecked
            config.needRestart = true
        }
        settingsChangeColourTopBarHolder.setOnClickListener {
            settingsChangeColourTopBar.toggle()
        }
    }

    private fun setupUseColoredContacts() = binding.apply {
        settingsColoredContacts.isChecked = config.useColoredContacts
        settingsColoredContacts.setOnCheckedChangeListener { isChecked ->
            config.useColoredContacts = isChecked
            settingsContactColorListHolder.beVisibleIf(config.useColoredContacts)
            config.needRestart = true
        }
        settingsColoredContactsHolder.setOnClickListener {
            settingsColoredContacts.toggle()
        }
    }

    private fun setupContactsColorList() = binding.apply {
        settingsContactColorListHolder.beVisibleIf(config.useColoredContacts)
        settingsContactColorListIcon.setImageResource(getContactsColorListIcon(config.contactColorList))
        settingsContactColorListHolder.setOnClickListener {
            val items = arrayListOf(
                com.goodwy.commons.R.drawable.ic_color_list,
                com.goodwy.commons.R.drawable.ic_color_list_android,
                com.goodwy.commons.R.drawable.ic_color_list_ios,
                com.goodwy.commons.R.drawable.ic_color_list_arc
            )
            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")

            IconListDialog(
                activity = this@SettingsActivity,
                items = items,
                checkedItemId = config.contactColorList,
                defaultItemId = LBC_ANDROID,
                titleId = com.goodwy.strings.R.string.overflow_icon,
                blurTarget = blurTarget
            ) { wasPositivePressed, newValue ->
                if (wasPositivePressed) {
                    if (config.contactColorList != newValue) {
                        config.contactColorList = newValue
                        settingsContactColorListIcon.setImageResource(getContactsColorListIcon(config.contactColorList))
                        config.needRestart = true
                    }
                }
            }
        }
    }

    private fun setupColorSimIcons() = binding.apply {
        settingsColorSimCardIconsHolder.beGoneIf(!areMultipleSIMsAvailable())
        settingsColorSimCardIcons.isChecked = config.colorSimIcons
        settingsColorSimCardIcons.setOnCheckedChangeListener { isChecked ->
            config.colorSimIcons = isChecked
            settingsSimCardColorListHolder.beVisibleIf(config.colorSimIcons)
        }
        settingsColorSimCardIconsHolder.setOnClickListener {
            settingsColorSimCardIcons.toggle()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupSimCardColorList() = binding.apply {
        settingsSimCardColorListHolder.beVisibleIf(config.colorSimIcons && areMultipleSIMsAvailable())
        settingsSimCardColorListIcon1.setColorFilter(config.simIconsColors[1])
        settingsSimCardColorListIcon2.setColorFilter(config.simIconsColors[2])
        if (isPro()) {
            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            settingsSimCardColorListIcon1.setOnClickListener {
                ColorPickerDialog(
                    this@SettingsActivity,
                    config.simIconsColors[1],
                    addDefaultColorButton = true,
                    colorDefault = resources.getColor(com.goodwy.commons.R.color.ic_dialer, theme),
                    title = resources.getString(com.goodwy.strings.R.string.color_sim_card_icons),
                    blurTarget = blurTarget
                ) { wasPositivePressed, color, wasDefaultPressed ->
                    if (wasPositivePressed || wasDefaultPressed) {
                        if (hasColorChanged(config.simIconsColors[1], color)) {
                            addSimCardColor(1, color)
                            settingsSimCardColorListIcon1.setColorFilter(color)
                        }
                    }
                }
            }
            settingsSimCardColorListIcon2.setOnClickListener {
                ColorPickerDialog(
                    this@SettingsActivity,
                    config.simIconsColors[2],
                    addDefaultColorButton = true,
                    colorDefault = resources.getColor(com.goodwy.commons.R.color.color_primary, theme),
                    title = resources.getString(com.goodwy.strings.R.string.color_sim_card_icons),
                    blurTarget = blurTarget
                ) { wasPositivePressed, color, wasDefaultPressed ->
                    if (wasPositivePressed || wasDefaultPressed) {
                        if (hasColorChanged(config.simIconsColors[2], color)) {
                            addSimCardColor(2, color)
                            settingsSimCardColorListIcon2.setColorFilter(color)
                        }
                    }
                }
            }
        } else {
            settingsSimCardColorListLabel.text =
                "${getString(com.goodwy.commons.R.string.change_color)} (${getString(com.goodwy.commons.R.string.feature_locked)})"
            arrayOf(
                settingsSimCardColorListIcon1,
                settingsSimCardColorListIcon2
            ).forEach { view ->
                view.setOnClickListener {
                    RxAnimation.from(view)
                        .shake(shakeTranslation = 2f)
                        .subscribe()

                    showSnackbar(binding.root)
                }
            }
        }
    }

    private fun addSimCardColor(index: Int, color: Int) {
        val recentColors = config.simIconsColors

        recentColors.removeAt(index)
        recentColors.add(index, color)

        baseConfig.simIconsColors = recentColors
    }

    private fun hasColorChanged(old: Int, new: Int) = abs(old - new) > 1

    private fun setupTipJar() = binding.apply {
        settingsTipJarHolder.apply {
            beVisibleIf(isPro())
            background.applyColorFilter(getColoredMaterialStatusBarColor())
            setOnClickListener {
                launchPurchase()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupAbout() = binding.apply {
        settingsAboutVersion.text = "Version: " + BuildConfig.VERSION_NAME
        settingsAboutHolder.setOnClickListener {
            launchAbout()
        }
    }

    private fun updatePro(isPro: Boolean = isPro()) {
        binding.apply {
            settingsTipJarHolder.beVisibleIf(isPro)

            val stringId =
                if (isRTLLayout) com.goodwy.strings.R.string.swipe_right_action
                else com.goodwy.strings.R.string.swipe_left_action
            settingsSwipeLeftActionLabel.text = addLockedLabelIfNeeded(stringId, isPro)
            settingsSwipeLeftActionHolder.alpha = if (isPro) 1f else 0.4f
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        updateMenuItemColors(menu)
        return super.onCreateOptionsMenu(menu)
    }

    private fun setupOptionsMenu() {
        binding.settingsMenu.toolbar?.apply {
            inflateMenu(R.menu.menu_settings)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.whats_new -> {
                        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                            ?: throw IllegalStateException("mainBlurTarget not found")
                        WhatsNewDialog(this@SettingsActivity, whatsNewList(), blurTarget = blurTarget)
                        true
                    }
                    else -> false
                }
            }
        }
    }
}
