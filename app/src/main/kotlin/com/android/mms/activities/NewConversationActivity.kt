package com.android.mms.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionInfo
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.SimpleContact
import com.android.mms.R
import com.android.mms.adapters.ContactsAdapter
import com.android.mms.adapters.ContactPhonePair
import com.android.mms.databinding.ActivityNewConversationBinding
import com.android.mms.databinding.ItemSuggestedContactBinding
import com.android.mms.extensions.*
import com.android.mms.helpers.*
import com.android.mms.messaging.isShortCodeWithLetters
import com.android.mms.messaging.sendMessageCompat
import com.android.mms.models.Attachment
import com.android.mms.models.SIMCard
import com.android.mms.helpers.MessageHolderHelper
import java.net.URLDecoder
import java.util.Locale
import java.util.Objects

class NewConversationActivity : SimpleActivity() {
    private val debugTag = "NewConversationFee"
    private var allContacts = ArrayList<SimpleContact>()
    private var privateContacts = ArrayList<SimpleContact>()
    private var isSpeechToTextAvailable = false
    private var isAttachmentPickerVisible = false
    private var messageHolderHelper: MessageHolderHelper? = null
    private var expandedMessageFragment: com.android.mms.fragments.ExpandedMessageFragment? = null
    // Map to store chip display text -> phone number mapping
    private val chipDisplayToPhoneNumber = mutableMapOf<String, String>()
    // Flag to prevent recursive calls when updating chips
    private var isUpdatingChips = false
    private val availableSIMCards = ArrayList<SIMCard>()
    private var currentSIMCardIndex = 0

    private val binding by viewBinding(ActivityNewConversationBinding::inflate)
    
    companion object {
        private const val PICK_SAVE_FILE_INTENT = 1008
        private const val PICK_SAVE_DIR_INTENT = 1009
        private const val REQUEST_CODE_CONTACT_PICKER = 1010
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.new_conversation)
        updateTextColors(binding.newConversationHolder)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.contactsList, binding.messageHolder.root))
//        setupMaterialScrollListener(
//            scrollingView = binding.contactsList,
//            topAppBar = binding.newConversationAppbar
//        )

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        binding.newConversationAddress.requestEditTextFocus()
        binding.newConversationAddress.hint = getString(R.string.add_contact_or_number)

        // READ_CONTACTS permission is not mandatory, but without it we won't be able to show any suggestions during typing
        handlePermission(PERMISSION_READ_CONTACTS) {
            initContacts()
        }
    }

    override fun onResume() {
        super.onResume()
        val getProperPrimaryColor = getProperPrimaryColor()

        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        setupTopAppBar(binding.newConversationAppbar, NavigationIcon.Arrow, topBarColor = backgroundColor)
        binding.newConversationHolder.setBackgroundColor(backgroundColor)

        binding.noContactsPlaceholder2.setTextColor(getProperPrimaryColor)
        binding.noContactsPlaceholder2.underlineText()
        binding.suggestionsLabel.setTextColor(getProperPrimaryColor)

        binding.contactsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                hideKeyboard()
            }
        })
        
        setupMessageHolder()
        handlePermission(PERMISSION_READ_PHONE_STATE) {
            if (it) {
                setupSIMSelector()
            }
        }
    }
    
    private fun setupMessageHolder() {
        isSpeechToTextAvailable = isSpeechToTextAvailable()
        
        messageHolderHelper = MessageHolderHelper(
            activity = this,
            binding = binding.messageHolder,
            onSendMessage = { text, subscriptionId, attachments ->
                sendMessageAndNavigate(text, subscriptionId, attachments)
            },
            onSpeechToText = { speechToText() },
            onExpandMessage = { showExpandedMessageFragment() }
        )
        
        messageHolderHelper?.setup(isSpeechToTextAvailable)
        
        binding.messageHolder.apply {
            threadAddAttachmentHolder.setOnClickListener {
                if (attachmentPickerHolder.isVisible()) {
                    isAttachmentPickerVisible = false
                    messageHolderHelper?.hideAttachmentPicker()
                } else {
                    isAttachmentPickerVisible = true
                    messageHolderHelper?.showAttachmentPicker()
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
                onScheduleMessage = null,
                onPickQuickText = {
                    val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                        ?: throw IllegalStateException("mainBlurTarget not found")
                    com.android.mms.dialogs.QuickTextSelectionDialog(this@NewConversationActivity, blurTarget) { selectedText ->
                        messageHolderHelper?.insertText(selectedText)
                    }
                }
            )
            
            messageHolderHelper?.hideAttachmentPicker()
        }
        
        // Handle forwarded messages from Intent.ACTION_SEND or Intent.ACTION_SEND_MULTIPLE
        handleForwardedMessage()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode != RESULT_OK) return
        
        if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultData != null) {
            val res: java.util.ArrayList<String> =
                resultData.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) as java.util.ArrayList<String>
            val speechToText = Objects.requireNonNull(res)[0]
            if (speechToText.isNotEmpty()) {
                messageHolderHelper?.setMessageText(speechToText)
            }
        } else if (requestCode == REQUEST_CODE_CONTACT_PICKER && resultData != null) {
            val displayTexts = ContactPickerActivity.getSelectedDisplayTexts(resultData)
            val normalizedNumbers = ContactPickerActivity.getSelectedPhoneNumbers(resultData)
            if (displayTexts.size == normalizedNumbers.size) {
                isUpdatingChips = true
                for (i in displayTexts.indices) {
                    val displayText = displayTexts[i]
                    val normalizedNumber = normalizedNumbers[i]
                    if (displayText.isNotEmpty() && normalizedNumber.isNotEmpty() && !chipDisplayToPhoneNumber.containsKey(displayText)) {
                        chipDisplayToPhoneNumber[displayText] = normalizedNumber
                        binding.newConversationAddress.addChip(displayText)
                    }
                }
                isUpdatingChips = false
            }
        } else {
            messageHolderHelper?.handleActivityResult(requestCode, resultCode, resultData)
            
            if (requestCode == MessageHolderHelper.PICK_CONTACT_INTENT && resultData?.data != null) {
                addContactAttachment(resultData.data!!)
            }
        }
    }

    private fun initContacts() {
        if (isThirdPartyIntent()) {
            return
        }

        fetchContacts()

        isSpeechToTextAvailable = isSpeechToTextAvailable()

        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val surfaceColor = if (useSurfaceColor) getProperBackgroundColor() else getSurfaceColor()
        val properTextColor = getProperTextColor()
        val properAccentColor = getProperAccentColor()
        
        binding.newConversationAddress.setColors(properTextColor, properAccentColor, surfaceColor)
        binding.newConversationAddress.getEditText().setBackgroundResource(com.goodwy.commons.R.drawable.search_bg)
        binding.newConversationAddress.getEditText().backgroundTintList = ColorStateList.valueOf(surfaceColor)
        
        // Listen for chip addition to validate newly added chips
        binding.newConversationAddress.setOnChipAddedListener { chipText ->
            if (chipDisplayToPhoneNumber.containsKey(chipText)) {
                return@setOnChipAddedListener true
            }
            // Validate the newly added chip
            if (!isValidForChip(chipText)) {
                isUpdatingChips = true
                binding.newConversationAddress.removeChip(chipText)
                isUpdatingChips = false
                toast(R.string.invalid_contact_or_number, length = Toast.LENGTH_SHORT)
                return@setOnChipAddedListener false
            }
            return@setOnChipAddedListener true
        }
        
        // Listen for chip changes to handle typed phone numbers and contact names
        binding.newConversationAddress.setOnChipsChangedListener { chips ->
            if (isUpdatingChips) return@setOnChipsChangedListener

            // Keep mapping in sync with visible chips so deleted chips can be re-added later.
            val activeChips = chips.toSet()
            val staleKeys = chipDisplayToPhoneNumber.keys.filter { key -> !activeChips.contains(key) }
            staleKeys.forEach { staleKey ->
                chipDisplayToPhoneNumber.remove(staleKey)
            }
            
            chips.forEach { chipText ->
                // If this chip is not in our mapping, check if it's a phone number or contact name
                if (!chipDisplayToPhoneNumber.containsKey(chipText)) {
                    // First check if it's a contact name (prioritize contact names over phone numbers)
                    val contact = findContactByName(chipText)
                    if (contact != null) {
                        // Found a contact with this name, handle phone number selection
                        handleContactNameChip(chipText, contact)
                    } else {
                        // Not a contact name, check if it's a phone number
                        val normalizedNumber = chipText.normalizePhoneNumber()
                        // Check if normalization produced a meaningful result (has digits and reasonable length)
                        if (normalizedNumber.isNotEmpty() && normalizedNumber.length >= 3 && normalizedNumber.all { it.isDigit() }) {
                            // Look up contact and phone number object for this phone number
                            val contactAndPhoneNumber = findContactAndPhoneNumberByNormalizedNumber(normalizedNumber)
                            if (contactAndPhoneNumber != null) {
                                val (contact, phoneNumber) = contactAndPhoneNumber
                                // Contact found, update the chip with name and type if needed
                                val displayText = getDisplayTextForPhoneNumberWithType(phoneNumber, contact)
                                isUpdatingChips = true
                                binding.newConversationAddress.removeChip(chipText)
                                binding.newConversationAddress.addChip(displayText)
                                chipDisplayToPhoneNumber[displayText] = normalizedNumber
                                isUpdatingChips = false
                            } else {
                                // No contact found, store the mapping (chipText -> normalizedNumber)
                                // This handles both cases: chipText might be the original or already normalized
                                chipDisplayToPhoneNumber[chipText] = normalizedNumber
                            }
                        }
                    }
                }
            }
            
            // Update SIM selector when chips change
            handlePermission(PERMISSION_READ_PHONE_STATE) {
                if (it) {
                    setupSIMSelector()
                }
            }
        }

        binding.newConversationAddress.setOnTextChangedListener { searchString ->
            // Hide/show suggestions based on text input
            if (searchString.isNotEmpty()) {
                binding.suggestionsLabel.beGone()
                binding.suggestionsScrollview.beGone()
            } else {
                // Show suggestions only if they exist (check if suggestionsHolder has children)
                if (binding.suggestionsHolder.childCount > 0) {
                    binding.suggestionsLabel.beVisible()
                    binding.suggestionsScrollview.beVisible()
                }
            }
            
            val filteredContacts = ArrayList<SimpleContact>()
            allContacts.forEach { contact ->
                // Check if contact name matches
                val nameMatches = contact.name.contains(searchString, true) ||
                    contact.name.contains(searchString.normalizeString(), true) ||
                    contact.name.normalizeString().contains(searchString, true)
                
                // Check if any phone number matches
                val phoneMatches = contact.phoneNumbers.any { 
                    it.normalizedNumber.contains(searchString, true) ||
                    it.value.contains(searchString, true)
                }
                
                if (nameMatches || phoneMatches) {
                    filteredContacts.add(contact)
                }
            }

            filteredContacts.sortWith(compareBy { !it.name.startsWith(searchString, true) })
            setupAdapter(filteredContacts)
        }

        binding.newConversationAddress.setSpeechToTextButtonVisible(false)
        binding.newConversationAddress.setSpeechToTextButtonClickListener { speechToText() }
        binding.newConversationAddress.setAddressBookButtonClickListener { launchContactPicker() }

        binding.noContactsPlaceholder2.setOnClickListener {
            handlePermission(PERMISSION_READ_CONTACTS) {
                if (it) {
                    fetchContacts()
                }
            }
        }

        binding.contactsLetterFastscroller.textColor = properTextColor.getColorStateList()
        binding.contactsLetterFastscroller.pressedTextColor = properAccentColor
        binding.contactsLetterFastscrollerThumb.setupWithFastScroller(binding.contactsLetterFastscroller)
        binding.contactsLetterFastscrollerThumb.textColor = properAccentColor.getContrastColor()
        binding.contactsLetterFastscrollerThumb.thumbColor = properAccentColor.getColorStateList()
    }

    private fun isThirdPartyIntent(): Boolean {
        val result = SmsIntentParser.parse(intent)
        if (result != null) {
            val (body, recipients) = result
            launchThreadActivity(
                phoneNumber = URLDecoder.decode(recipients.replace("+", "%2b").trim()),
                name = "",
                body = body
            )
            finish()
            return true
        }
        return false
    }

    private fun fetchContacts() {
        fillSuggestedContacts {
//            SimpleContactsHelper(this).getAvailableContacts(false) {
                allContacts = it

                if (privateContacts.isNotEmpty()) {
                    // Deduplicate contacts before adding privateContacts
                    // Contacts from different sources may have different rawId but same phone numbers
                    val existingPhoneNumbers = HashSet<String>()
                    val existingContactNamesWithoutPhone = HashSet<String>()
                    allContacts.forEach { contact ->
                        contact.phoneNumbers.forEach { phoneNumber ->
                            existingPhoneNumbers.add(phoneNumber.normalizedNumber)
                        }
                        // Track names only for contacts without phone numbers (for fallback deduplication)
                        if (contact.name.isNotEmpty() && contact.phoneNumbers.isEmpty()) {
                            existingContactNamesWithoutPhone.add(contact.name.lowercase().trim())
                        }
                    }
                    
                    // Only add private contacts that don't already exist (by phone number, or by name if no phone)
                    privateContacts.forEach { privateContact ->
                        val hasMatchingPhoneNumber = privateContact.phoneNumbers.isNotEmpty() && 
                            privateContact.phoneNumbers.any { phoneNumber ->
                                existingPhoneNumbers.contains(phoneNumber.normalizedNumber)
                            }
                        // Only check name if contact has no phone numbers (to avoid false positives)
                        val hasMatchingName = privateContact.phoneNumbers.isEmpty() && 
                            privateContact.name.isNotEmpty() && 
                            existingContactNamesWithoutPhone.contains(privateContact.name.lowercase().trim())
                        
                        // Skip if duplicate by phone number or (if no phone) by name
                        if (!hasMatchingPhoneNumber && !hasMatchingName) {
                            allContacts.add(privateContact)
                            // Add its phone numbers and name to the sets for future checks
                            privateContact.phoneNumbers.forEach { phoneNumber ->
                                existingPhoneNumbers.add(phoneNumber.normalizedNumber)
                            }
                            if (privateContact.name.isNotEmpty() && privateContact.phoneNumbers.isEmpty()) {
                                existingContactNamesWithoutPhone.add(privateContact.name.lowercase().trim())
                            }
                        }
                    }
                    allContacts.sort()
                }

                runOnUiThread {
                    setupAdapter(allContacts)
                }
//            }
        }
    }

    private fun setupAdapter(contacts: ArrayList<SimpleContact>) {
        // Expand contacts with multiple phone numbers into separate entries
        val contactPhonePairs = ArrayList<ContactPhonePair>()
        contacts.forEach { contact ->
            if (contact.phoneNumbers.isEmpty()) {
                // Contact with no phone numbers - skip it
                return@forEach
            } else if (contact.phoneNumbers.size == 1) {
                // Single phone number - add as single entry
                contactPhonePairs.add(ContactPhonePair(contact, contact.phoneNumbers.first()))
            } else {
                // Multiple phone numbers - add each as separate entry
                contact.phoneNumbers.forEach { phoneNumber ->
                    contactPhonePairs.add(ContactPhonePair(contact, phoneNumber))
                }
            }
        }
        
        val hasContacts = contactPhonePairs.isNotEmpty()
        binding.contactsList.beVisibleIf(hasContacts)
        binding.noContactsPlaceholder.beVisibleIf(!hasContacts)
        binding.noContactsPlaceholder2.beVisibleIf(
            !hasContacts && !hasPermission(
                PERMISSION_READ_CONTACTS
            )
        )

        if (!hasContacts) {
            val placeholderText = if (hasPermission(PERMISSION_READ_CONTACTS)) {
                com.goodwy.commons.R.string.no_contacts_found
            } else {
                com.goodwy.commons.R.string.no_access_to_contacts
            }

            binding.noContactsPlaceholder.text = getString(placeholderText)
        }

        val currAdapter = binding.contactsList.adapter
        if (currAdapter == null) {
            ContactsAdapter(this, contactPhonePairs, binding.contactsList) {
                hideKeyboard()
                val contactPhonePair = it as ContactPhonePair
                val contact = contactPhonePair.contact
                val phoneNumber = contactPhonePair.phoneNumber
                // Directly add the phone number since each entry is already specific
                val displayText = getDisplayTextForPhoneNumberWithType(phoneNumber, contact)
                // Set mapping BEFORE adding chip to prevent listener from re-triggering
                chipDisplayToPhoneNumber[displayText] = phoneNumber.normalizedNumber
                isUpdatingChips = true
                binding.newConversationAddress.addChip(displayText)
                isUpdatingChips = false
                binding.newConversationAddress.clearText()
            }.apply {
                binding.contactsList.adapter = this
            }

            if (areSystemAnimationsEnabled) {
                binding.contactsList.scheduleLayoutAnimation()
            }
        } else {
            (currAdapter as ContactsAdapter).updateContacts(contactPhonePairs)
        }

        setupLetterFastscroller(contactPhonePairs)
    }

    private fun fillSuggestedContacts(callback: (ArrayList<SimpleContact>) -> Unit) {
        val privateCursor = getMyContactsCursor(false, true)
        ensureBackgroundThread {
            SimpleContactsHelper(this).getAvailableContacts(false) {
                privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
                val contacts =  ArrayList(it + privateContacts)
                val suggestions = getSuggestedContacts(contacts)
                runOnUiThread {
                    binding.suggestionsHolder.removeAllViews()
                    if (suggestions.isEmpty()) {
                        binding.suggestionsLabel.beGone()
                        binding.suggestionsScrollview.beGone()
                    } else {
                        //binding.suggestionsLabel.beVisible()
                        binding.suggestionsScrollview.beVisible()
                        suggestions.forEach { contact ->
                            ItemSuggestedContactBinding.inflate(layoutInflater).apply {
                                suggestedContactName.text = contact.name
                                suggestedContactName.setTextColor(getProperTextColor())
                                
                                // Display phone numbers
                                val phoneNumbersText = if (contact.phoneNumbers.isNotEmpty()) {
                                    TextUtils.join(", ", contact.phoneNumbers.map { it.value })
                                } else {
                                    ""
                                }
                                suggestedContactNumber.text = phoneNumbersText
                                suggestedContactNumber.setTextColor(getProperTextColor())
                                suggestedContactNumber.beVisibleIf(phoneNumbersText.isNotEmpty())

                                if (!isDestroyed) {
                                    if (contact.isABusinessContact() && contact.photoUri == "") {
                                        val drawable =
                                            SimpleContactsHelper(this@NewConversationActivity).getColoredCompanyIcon(contact.name)
                                        suggestedContactImage.setImageDrawable(drawable)
                                    } else {
                                        SimpleContactsHelper(this@NewConversationActivity).loadContactImage(
                                            contact.photoUri,
                                            suggestedContactImage,
                                            contact.name
                                        )
                                    }
                                    binding.suggestionsHolder.addView(root)
                                    root.setOnClickListener {
                                        hideKeyboard()
                                        // Handle multiple phone numbers by showing picker dialog
                                        maybeShowNumberPickerDialog(contact.phoneNumbers) { number ->
                                            // Directly add the phone number since each entry is already specific
                                            val displayText = getDisplayTextForPhoneNumberWithType(number)
                                            // Set mapping BEFORE adding chip to prevent listener from re-triggering
                                            chipDisplayToPhoneNumber[displayText] = number.normalizedNumber
                                            isUpdatingChips = true
                                            binding.newConversationAddress.addChip(displayText)
                                            isUpdatingChips = false
                                            binding.newConversationAddress.clearText()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    callback(it)
                }
            }
        }
    }

    private fun setupLetterFastscroller(contactPhonePairs: ArrayList<ContactPhonePair>) {
        try {
            //Decrease the font size based on the number of letters in the letter scroller
            val allNotEmpty = contactPhonePairs.filter { it.contact.name.isNotEmpty() }
            val all = allNotEmpty.map { it.contact.name.substring(0, 1) }
            val unique: Set<String> = HashSet(all)
            val sizeUnique = unique.size
            if (isHighScreenSize()) {
                if (sizeUnique > 48) binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTooTiny
                else if (sizeUnique > 37) binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTiny
                else binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleSmall
            } else {
                if (sizeUnique > 36) binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTooTiny
                else if (sizeUnique > 30) binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTiny
                else binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleSmall
            }
        } catch (_: Exception) { }

        binding.contactsLetterFastscroller.setupWithRecyclerView(binding.contactsList, { position ->
            try {
                val name = contactPhonePairs[position].contact.name
                val emoji = name.take(2)
                val character = if (emoji.isEmoji()) emoji else if (name.isNotEmpty()) name.substring(0, 1) else ""
                FastScrollItemIndicator.Text(
                    character.uppercase(Locale.getDefault()).normalizeString()
                )
            } catch (_: Exception) {
                FastScrollItemIndicator.Text("")
            }
        })
    }

    private fun isHighScreenSize(): Boolean {
        return when (resources.configuration.screenLayout
            and Configuration.SCREENLAYOUT_LONG_MASK) {
            Configuration.SCREENLAYOUT_LONG_NO -> false
            else -> true
        }
    }

    /**
     * Gets the display text for a phone number.
     * Returns contact name if found in contacts, otherwise returns the phone number.
     */
    private fun getDisplayTextForPhoneNumber(phoneNumber: String): String {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) {
            return phoneNumber
        }

        // First check in allContacts
        allContacts.forEach { contact ->
            if (contact.phoneNumbers.any { it.normalizedNumber == phoneNumber }) {
                return contact.name.ifEmpty { phoneNumber }
            }
        }

        // Then check in privateContacts
        privateContacts.forEach { contact ->
            if (contact.phoneNumbers.any { it.normalizedNumber == phoneNumber }) {
                return contact.name.ifEmpty { phoneNumber }
            }
        }

        // If not found in loaded contacts, try using SimpleContactsHelper
        val contactName = SimpleContactsHelper(this).getNameFromPhoneNumber(phoneNumber)
        return if (contactName != phoneNumber) contactName else phoneNumber
    }

    /**
     * Gets the display text for a phone number with type information.
     * This allows multiple phone numbers from the same contact to be added as separate chips.
     * Format: "Contact Name (Phone Type)" if contact has multiple numbers, otherwise just "Contact Name".
     * If multiple numbers have the same type, includes the phone number value to make them unique.
     */
    private fun getDisplayTextForPhoneNumberWithType(phoneNumber: PhoneNumber, contact: SimpleContact? = null): String {
        val contactToUse = contact ?: findContactByPhoneNumber(phoneNumber.normalizedNumber)
        
        if (contactToUse == null) {
            // No contact found, return the phone number
            return phoneNumber.normalizedNumber
        }

        val contactName = contactToUse.name.ifEmpty { phoneNumber.normalizedNumber }
        
        // If contact has multiple phone numbers, include the type to make chips unique
        if (contactToUse.phoneNumbers.size > 1) {
            return "$contactName (${phoneNumber.value})"
        }
        
        // Single phone number, just return the contact name
        return contactName
    }

    /**
     * Finds a contact by phone number.
     * Returns the contact if found, or null otherwise.
     */
    private fun findContactByPhoneNumber(phoneNumber: String): SimpleContact? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) {
            return null
        }

        // First check in allContacts
        allContacts.forEach { contact ->
            if (contact.phoneNumbers.any { it.normalizedNumber == phoneNumber }) {
                return contact
            }
        }

        // Then check in privateContacts
        privateContacts.forEach { contact ->
            if (contact.phoneNumbers.any { it.normalizedNumber == phoneNumber }) {
                return contact
            }
        }

        return null
    }

    /**
     * Finds a contact and the specific PhoneNumber object by normalized phone number.
     * Returns a Pair of (SimpleContact, PhoneNumber) if found, or null otherwise.
     */
    private fun findContactAndPhoneNumberByNormalizedNumber(normalizedNumber: String): Pair<SimpleContact, PhoneNumber>? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) {
            return null
        }

        // First check in allContacts
        allContacts.forEach { contact ->
            contact.phoneNumbers.forEach { phoneNumber ->
                if (phoneNumber.normalizedNumber == normalizedNumber) {
                    return Pair(contact, phoneNumber)
                }
            }
        }

        // Then check in privateContacts
        privateContacts.forEach { contact ->
            contact.phoneNumbers.forEach { phoneNumber ->
                if (phoneNumber.normalizedNumber == normalizedNumber) {
                    return Pair(contact, phoneNumber)
                }
            }
        }

        return null
    }

    /**
     * Finds a contact by name (case-insensitive, prioritizes exact matches).
     * Returns the first matching contact, or null if not found.
     */
    private fun findContactByName(name: String): SimpleContact? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) {
            return null
        }

        val searchName = name.trim().lowercase()
        if (searchName.isEmpty()) {
            return null
        }

        // First try exact match in allContacts
        allContacts.forEach { contact ->
            val contactName = contact.name.lowercase()
            if (contactName == searchName) {
                return contact
            }
        }

        // Then try exact match in privateContacts
        privateContacts.forEach { contact ->
            val contactName = contact.name.lowercase()
            if (contactName == searchName) {
                return contact
            }
        }

        // If no exact match, try partial matches (contact name contains search text)
        allContacts.forEach { contact ->
            val contactName = contact.name.lowercase()
            if (contactName.contains(searchName)) {
                return contact
            }
        }

        // Try partial matches in privateContacts
        privateContacts.forEach { contact ->
            val contactName = contact.name.lowercase()
            if (contactName.contains(searchName)) {
                return contact
            }
        }

        return null
    }

    /**
     * Validates if text can be added as a chip.
     * Returns true if:
     * - Text contains only numbers and phone formatting characters (+, -, spaces, parentheses), OR
     * - Text contains non-numbers but matches a contact
     * Returns false if:
     * - Text contains non-numbers AND doesn't match any contact
     */
    private fun isValidForChip(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return false
        }

        // Check if the original text contains only digits and common phone formatting characters
        // Phone formatting characters: +, -, spaces, parentheses, #, *
        val phoneFormatChars = setOf('+', '-', ' ', '(', ')', '#', '*')
        val containsOnlyNumbersAndFormatting = trimmed.all { it.isDigit() || phoneFormatChars.contains(it) }
        
        // If it contains only numbers and formatting, it's valid (will be normalized to digits)
        if (containsOnlyNumbersAndFormatting) {
            return true
        }

        // If it contains non-numeric characters (letters, etc.), check if it matches a contact
        // This prevents invalid text like "abc123" from being added unless it's a contact name
        val contact = findContactByName(trimmed)
        return contact != null
    }

    /**
     * Handles adding a chip when a contact name is typed.
     * Shows number picker dialog if contact has multiple phone numbers.
     */
    private fun handleContactNameChip(chipText: String, contact: SimpleContact) {
        if (contact.phoneNumbers.isEmpty()) {
            // Contact has no phone numbers, remove the chip
            isUpdatingChips = true
            binding.newConversationAddress.removeChip(chipText)
            isUpdatingChips = false
            toast(com.goodwy.commons.R.string.no_phone_number_found, length = Toast.LENGTH_SHORT)
            return
        }

        if (contact.phoneNumbers.size == 1) {
            // Single phone number, add chip directly
            val phoneNumber = contact.phoneNumbers.first()
            val displayText = getDisplayTextForPhoneNumberWithType(phoneNumber, contact)
            isUpdatingChips = true
            chipDisplayToPhoneNumber[displayText] = phoneNumber.normalizedNumber
            binding.newConversationAddress.removeChip(chipText)
            binding.newConversationAddress.addChip(displayText)
            isUpdatingChips = false
        } else {
            // Multiple phone numbers, show picker dialog
            isUpdatingChips = true
            binding.newConversationAddress.removeChip(chipText)
            isUpdatingChips = false
            
            maybeShowNumberPickerDialog(contact.phoneNumbers) { number ->
                val displayText = getDisplayTextForPhoneNumberWithType(number, contact)
                isUpdatingChips = true
                chipDisplayToPhoneNumber[displayText] = number.normalizedNumber
                binding.newConversationAddress.addChip(displayText)
                isUpdatingChips = false
            }
        }
    }

    
    private fun sendMessageAndNavigate(text: String, subscriptionId: Int?, attachments: List<Attachment>) {
        hideKeyboard()
        
        val chips = binding.newConversationAddress.allChips
        val allNumbers = mutableListOf<String>()
        chips.forEach { chip ->
            if (chip.isNotEmpty()) {
                // Get phone number from mapping
                val phoneNumber = chipDisplayToPhoneNumber[chip]
                if (phoneNumber != null) {
                    // Mapping exists, use the stored phone number
                    if (phoneNumber.isNotEmpty() && !allNumbers.contains(phoneNumber)) {
                        allNumbers.add(phoneNumber)
                    }
                } else {
                    // No mapping found - this shouldn't happen for properly added chips,
                    // but handle it as fallback: try to normalize the chip text
                    val normalizedNumber = chip.normalizePhoneNumber()
                    if (normalizedNumber.isNotEmpty() && normalizedNumber.length >= 3 && normalizedNumber.all { it.isDigit() }) {
                        if (!allNumbers.contains(normalizedNumber)) {
                            allNumbers.add(normalizedNumber)
                        }
                    } else {
                        // Not a valid phone number, try to look up contact name
                        val contact = findContactByName(chip)
                        if (contact != null && contact.phoneNumbers.isNotEmpty()) {
                            // Found contact, use first phone number
                            val contactPhoneNumber = contact.phoneNumbers.first().normalizedNumber
                            if (!allNumbers.contains(contactPhoneNumber)) {
                                allNumbers.add(contactPhoneNumber)
                            }
                        }
                    }
                }
            }
        }
        
        // Also check for text input that hasn't been added as a chip
        val currentText = binding.newConversationAddress.currentText.trim()
        if (currentText.isNotEmpty()) {
            // Split by comma or semicolon to handle multiple numbers
            val textNumbers = currentText.split(",", ";")
            // Validate each part before processing
            textNumbers.forEach { numberText ->
                val trimmedNumber = numberText.trim()
                if (trimmedNumber.isNotEmpty()) {
                    // Validate using isValidForChip before processing
                    if (!isValidForChip(trimmedNumber)) {
                        toast(R.string.invalid_contact_or_number, length = Toast.LENGTH_SHORT)
                        return
                    }
                }
            }
            
            // If validation passed, process the numbers
            textNumbers.forEach { numberText ->
                val trimmedNumber = numberText.trim()
                if (trimmedNumber.isNotEmpty()) {
                    // Normalize the phone number
                    val normalizedNumber = trimmedNumber.normalizePhoneNumber()
                    // Add if not already in the list (avoid duplicates)
                    if (normalizedNumber.isNotEmpty() && !allNumbers.contains(normalizedNumber)) {
                        allNumbers.add(normalizedNumber)
                    } else if (normalizedNumber.isEmpty() && !allNumbers.contains(trimmedNumber)) {
                        // If normalization failed, use the original trimmed number
                        allNumbers.add(trimmedNumber)
                    }
                }
            }
        }
        
        if (allNumbers.isEmpty()) {
            toast(R.string.empty_destination_address, length = Toast.LENGTH_SHORT)
            return
        }
        
        // Get subscription ID for the numbers if not provided
        val finalSubscriptionId = subscriptionId 
            ?: availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
            ?: messageHolderHelper?.getSubscriptionIdForNumbers(allNumbers)
            ?: SmsManager.getDefaultSmsSubscriptionId()
        
        // Process message text (remove diacritics if needed)
        val processedText = removeDiacriticsIfNeeded(text)
        
        // Get thread ID before sending to delete draft
        val numbersSet = allNumbers.toSet()
        val threadId = getThreadId(numbersSet)
        
        // Send message
        try {
            sendMessageCompat(
                text = processedText,
                addresses = allNumbers,
                subId = finalSubscriptionId,
                attachments = attachments,
                messageId = null
            )
            
            // Play sound if enabled
            if (config.soundOnOutGoingMessages) {
                val audioManager = getSystemService(AudioManager::class.java)
                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
            }
            
            // Clear message and attachments
            messageHolderHelper?.clearMessage()
            
            // Delete any draft for this thread to prevent it from showing in ThreadActivity
            ensureBackgroundThread {
                deleteSmsDraft(threadId)
            }
            
            // Navigate to ThreadActivity after sending (don't pass body to avoid showing sent message)
            val numbersString = allNumbers.joinToString(";")
            val displayName = if (allNumbers.size == 1) allNumbers[0] else "${allNumbers.size} recipients"
            launchThreadActivity(numbersString, displayName, body = "")
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    
    private fun addContactAttachment(contactUri: Uri) {
        // Contact attachment functionality can be added later if needed
        toast(com.goodwy.commons.R.string.unknown_error_occurred)
    }
    
    private fun launchCapturePhotoIntent() {
        val imageFile = java.io.File.createTempFile("attachment_", ".jpg", getAttachmentsDir())
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

    private fun launchContactPicker() {
        handlePermission(PERMISSION_READ_CONTACTS) { granted ->
            if (granted) {
                hideKeyboard()
                startActivityForResult(Intent(this, ContactPickerActivity::class.java), REQUEST_CODE_CONTACT_PICKER)
            } else {
                toast(com.goodwy.commons.R.string.no_contacts_permission, length = Toast.LENGTH_LONG)
            }
        }
    }
    
    private fun launchActivityForResult(intent: Intent, requestCode: Int) {
        hideKeyboard()
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
    
    private fun getAttachmentsDir(): java.io.File {
        return java.io.File(cacheDir, "attachments").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private fun handleForwardedMessage() {
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            // Handle text
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                messageHolderHelper?.setMessageText(text)
                binding.messageHolder.threadTypeMessage.requestFocus()
            }
            
            // Handle single attachment
            if (intent.action == Intent.ACTION_SEND && intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                uri?.let {
                    messageHolderHelper?.addAttachment(it)
                }
            }
            
            // Handle multiple attachments
            if (intent.action == Intent.ACTION_SEND_MULTIPLE && intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                uris?.forEach { uri ->
                    messageHolderHelper?.addAttachment(uri)
                }
            }
        }
    }
    
    private fun launchThreadActivity(phoneNumber: String, name: String, body: String = "", photoUri: String = "") {
        hideKeyboard()
//        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("sms_body") ?: ""
        val numbers = phoneNumber.split(";").toSet()
        val number = if (numbers.size == 1) phoneNumber else Gson().toJson(numbers)
        Intent(this, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, getThreadId(numbers))
            putExtra(THREAD_TITLE, name)
            putExtra(THREAD_TEXT, body.ifEmpty { intent.getStringExtra(Intent.EXTRA_TEXT) })
            putExtra(THREAD_NUMBER, number)
            putExtra(THREAD_URI, photoUri)

            if (intent.action == Intent.ACTION_SEND && intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                putExtra(THREAD_ATTACHMENT_URI, uri?.toString())
            } else if (intent.action == Intent.ACTION_SEND_MULTIPLE && intent.extras?.containsKey(
                    Intent.EXTRA_STREAM
                ) == true
            ) {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                putExtra(THREAD_ATTACHMENT_URIS, uris)
            }

            startActivity(this)
            finish()
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
            // Get the message text and attachments, then send
            val messageText = binding.messageHolder.threadTypeMessage.text?.toString() ?: ""
            val attachments = messageHolderHelper?.buildMessageAttachments() ?: emptyList()
            val subscriptionId = messageHolderHelper?.getSubscriptionIdForNumbers(emptyList())
            sendMessageAndNavigate(messageText, subscriptionId, attachments)
        }
        
        expandedMessageFragment?.setOnMinimizeListener {
            val text = expandedMessageFragment?.getMessageText() ?: ""
            binding.messageHolder.threadTypeMessage.setText(text)
            hideExpandedMessageFragment()
        }
        
        expandedMessageFragment?.let { fragment ->
            // Hide the main content
            findViewById<View>(R.id.new_conversation_coordinator)?.beGone()
            
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit()
            
            // Update fragment title for new conversation
            fragment.view?.post {
                updateFragmentTitle(fragment)
            } ?: run {
                // If view is null, post with a small delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    fragment.view?.post {
                        updateFragmentTitle(fragment)
                    }
                }, 100)
            }
        }
    }
    
    private fun updateFragmentTitle(fragment: com.android.mms.fragments.ExpandedMessageFragment) {
        if (fragment.view == null) return
        
        // For new conversation, we don't have a thread title yet
        // Get recipient names from chips if available
        val chips = binding.newConversationAddress.allChips
        val recipientNames = chips.filter { it.isNotEmpty() }
        val threadTitle = if (recipientNames.isNotEmpty()) {
            recipientNames.joinToString(", ")
        } else {
            getString(R.string.new_conversation)
        }
        
        fragment.updateThreadTitle(
            threadTitle = threadTitle,
            threadSubtitle = "",
            threadTopStyle = config.threadTopStyle,
            showContactThumbnails = config.showContactThumbnails,
            conversationPhotoUri = "",
            conversationTitle = null,
            conversationPhoneNumber = null,
            isCompany = false,
            participantsCount = recipientNames.size
        )
    }
    
    private fun hideExpandedMessageFragment() {
        expandedMessageFragment?.let {
            supportFragmentManager.popBackStack()
            findViewById<View>(R.id.new_conversation_coordinator)?.beVisible()
            expandedMessageFragment = null
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
            // Get phone numbers from chips
            binding.newConversationAddress.allChips.forEach { chip ->
                if (chip.isNotEmpty()) {
                    val phoneNumber = chipDisplayToPhoneNumber[chip]
                    if (phoneNumber != null && phoneNumber.isNotEmpty() && !numbers.contains(phoneNumber)) {
                        numbers.add(phoneNumber)
                    } else {
                        // Try to normalize the chip text as fallback
                        val normalizedNumber = chip.normalizePhoneNumber()
                        if (normalizedNumber.isNotEmpty() && normalizedNumber.length >= 3 && normalizedNumber.all { it.isDigit() }) {
                            if (!numbers.contains(normalizedNumber)) {
                                numbers.add(normalizedNumber)
                            }
                        }
                    }
                }
            }

            // Determine SIM index - use numbers if available, otherwise use default
            currentSIMCardIndex = if (numbers.isNotEmpty()) {
                getProperSimIndex(availableSIMs, numbers)
            } else {
                // No numbers yet, use default SMS subscription or first SIM
                val defaultSmsSubscriptionId = SmsManager.getDefaultSmsSubscriptionId()
                val systemPreferredSimIdx = if (defaultSmsSubscriptionId >= 0) {
                    availableSIMs.indexOfFirstOrNull { it.subscriptionId == defaultSmsSubscriptionId }
                } else {
                    null
                }
                systemPreferredSimIdx ?: 0
            }

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
                    // Only save preference if we have phone numbers
                    if (numbers.isNotEmpty()) {
                        numbers.forEach {
                            config.saveUseSIMIdAtNumber(it, currentSubscriptionId)
                        }
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
        } else {
            binding.messageHolder.threadSelectSimIconHolder.beGone()
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

        val defaultSmsSubscriptionId = SmsManager.getDefaultSmsSubscriptionId()
        val systemPreferredSimIdx = if (defaultSmsSubscriptionId >= 0) {
            availableSIMs.indexOfFirstOrNull { it.subscriptionId == defaultSmsSubscriptionId }
        } else {
            null
        }

        return userPreferredSimIdx ?: systemPreferredSimIdx ?: 0
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
