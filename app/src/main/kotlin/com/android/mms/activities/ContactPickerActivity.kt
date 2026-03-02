package com.android.mms.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.PhoneLookup
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getColoredDrawableWithColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.views.MyRecyclerView
import com.goodwy.commons.views.MyTextView
import com.android.common.helper.IconItem
import com.android.common.view.MRippleToolBar
import com.android.common.view.MSearchView
import com.android.common.view.MVSideFrame
import com.android.mms.R
import com.android.mms.adapters.ContactPickerAdapter
import com.android.mms.models.Contact
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.views.BlurAppBarLayout
import eightbitlab.com.blurview.BlurTarget

class ContactPickerActivity : SimpleActivity() {

    companion object {
        private const val PERMISSION_REQUEST_READ_CONTACTS = 100
        private const val PERMISSION_REQUEST_READ_CALL_LOG = 101
        private const val CALL_LOG_LIMIT = 500
        const val EXTRA_SELECTED_CONTACTS = "selected_contacts"
        const val EXTRA_ALREADY_SELECTED_CONTACTS = "already_selected_contacts"
        const val EXTRA_SELECTED_DISPLAY_TEXTS = "selected_display_texts"
        const val EXTRA_SELECTED_PHONE_NUMBERS = "selected_phone_numbers"
        private const val BATCH_SIZE = 35

        fun getSelectedContacts(data: Intent?): ArrayList<Contact> {
            if (data != null && data.hasExtra(EXTRA_SELECTED_CONTACTS)) {
                @Suppress("UNCHECKED_CAST")
                val contacts = data.getParcelableArrayListExtra<Contact>(EXTRA_SELECTED_CONTACTS)
                return contacts ?: arrayListOf()
            }
            return arrayListOf()
        }

        fun getSelectedDisplayTexts(data: Intent?): ArrayList<String> {
            if (data != null && data.hasExtra(EXTRA_SELECTED_DISPLAY_TEXTS)) {
                return data.getStringArrayListExtra(EXTRA_SELECTED_DISPLAY_TEXTS) ?: arrayListOf()
            }
            return arrayListOf()
        }

        fun getSelectedPhoneNumbers(data: Intent?): ArrayList<String> {
            if (data != null && data.hasExtra(EXTRA_SELECTED_PHONE_NUMBERS)) {
                return data.getStringArrayListExtra(EXTRA_SELECTED_PHONE_NUMBERS) ?: arrayListOf()
            }
            return arrayListOf()
        }
    }

    private var scrollView: View? = null
    private var blurAppBarLayout: BlurAppBarLayout? = null
    private var totalOffset = 0
    private var rootView: View? = null
    private var contactRecyclerView: MyRecyclerView? = null
    private var contactAdapter: ContactPickerAdapter? = null
    private val allContacts = ArrayList<Contact>()
    private val filteredContacts = ArrayList<Contact>()
    private val selectedPositions = HashSet<Int>()
    private var searchString = ""
    private var contactsCursor: android.database.Cursor? = null
    private var isLoadingMore = false
    private var hasMoreContacts = true
    private val addedContactIds = HashSet<String>()
    private val alreadySelectedContactIds = HashSet<String>()
    private var bottomBarContainer: View? = null
    private var tabBar: MRippleToolBar? = null
    private var isCallLogMode = false
    private var filterCallLog: MyTextView? = null
    private var filterContacts: MyTextView? = null
    private var callLogPlaceholder: View? = null
    private var contactPickerFilterBar: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_picker)

        rootView = findViewById(R.id.root_view)
        initTheme()
        initMVSideFrames()
        initBouncy()
        initBouncyListener()
        initComponent()
        makeSystemBarsToTransparent()

        if (checkContactsPermission()) {
            loadContacts()
        }
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun initMVSideFrames() {
        val blurTarget = findViewById<BlurTarget>(R.id.blurTarget)
        findViewById<MVSideFrame>(R.id.m_vertical_side_frame_top).bindBlurTarget(blurTarget)
        findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom).bindBlurTarget(blurTarget)
    }

    override fun onResume() {
        super.onResume()
        // Match ThreadActivity: layout fullscreen so content draws behind transparent status/nav bars
        if (isSystemInDarkMode()) {
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
        // Use same background logic as MainActivity: surface color only for dynamic theme + light mode, else proper background
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        rootView?.setBackgroundColor(backgroundColor)
        findViewById<BlurTarget>(R.id.blurTarget)?.setBackgroundColor(backgroundColor)
        scrollView?.setBackgroundColor(backgroundColor)
        contactRecyclerView?.setBackgroundColor(backgroundColor)
        setupTopBarNavigation()
    }

    override fun onDestroy() {
        super.onDestroy()
        contactsCursor?.takeIf { !it.isClosed }?.close()
        contactsCursor = null
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bottomSideFrame = findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom)

        ViewCompat.setOnApplyWindowInsetsListener(rootView!!) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val dp5 = (5 * resources.displayMetrics.density).toInt()
            bottomSideFrame.layoutParams = bottomSideFrame.layoutParams.apply { height = navHeight + dp5 }

            val barContainer = bottomBarContainer
            if (barContainer != null) {
                val bottomBarLp = barContainer.layoutParams as ViewGroup.MarginLayoutParams
                val bottomOffset = dp(0)
                if (ime.bottom > 0) {
                    bottomBarLp.bottomMargin = ime.bottom + bottomOffset
                    contactRecyclerView?.setPadding(0, dp(150), 0, dp(40) + navHeight + ime.bottom)
                    contactRecyclerView?.scrollToPosition((contactAdapter?.itemCount ?: 1) - 1)
                } else {
                    bottomBarLp.bottomMargin = navHeight + bottomOffset
                    contactRecyclerView?.setPadding(0, dp(150), 0, dp(90) + navHeight)
                }
                barContainer.layoutParams = bottomBarLp
            }
            insets
        }
    }

    private fun initBouncy() {
        blurAppBarLayout = findViewById(R.id.blur_app_bar_layout)
        scrollView = findViewById(R.id.nest_scroll)
        blurAppBarLayout?.post {
            totalOffset = blurAppBarLayout?.totalScrollRange ?: 0
        }
    }

    private fun initBouncyListener() {
        blurAppBarLayout?.setupOffsetListener { verticalOffset, height ->
            val h = if (height > 0) height else 1
            blurAppBarLayout?.titleView?.scaleX = (1 + 0.7f * verticalOffset / h)
            blurAppBarLayout?.titleView?.scaleY = (1 + 0.7f * verticalOffset / h)
        }
    }

    private fun initComponent() {
        blurAppBarLayout?.setTitle(getString(R.string.select_contacts))
        setupTopBarNavigation()

        bottomBarContainer = findViewById(R.id.lyt_action)
        tabBar = findViewById(R.id.confirm_tab)

        val items = ArrayList<IconItem>().apply {
            add(IconItem().apply {
                icon = com.android.common.R.drawable.ic_cmn_cancel
                title = getString(com.android.common.R.string.cancel_common)
            })
            add(IconItem().apply {
                icon = R.drawable.ic_check_double_vector
                title = getString(com.android.common.R.string.confirm_common)
            })
        }
        val blurTarget = findViewById<BlurTarget>(R.id.blurTarget)
        tabBar?.setTabs(this, items, blurTarget)

        tabBar?.setOnClickedListener { index ->
            when (index) {
                0 -> {
                    setResult(RESULT_CANCELED)
                    finish()
                }
                1 -> returnSelectedContacts()
            }
        }

        blurAppBarLayout?.toolbar?.apply {
            inflateMenu(R.menu.menu_contact_picker)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.search) {
                    blurAppBarLayout?.startSearch()
                    true
                } else false
            }
        }
        blurAppBarLayout?.setOnSearchStateListener(object : BlurAppBarLayout.OnSearchStateListener {
            override fun onState(state: Int) {
                when (state) {
                    MSearchView.SEARCH_START -> {
                        hideTopBarNavigation()
                        contactRecyclerView?.isNestedScrollingEnabled = false
                        contactRecyclerView?.scrollToPosition((contactAdapter?.itemCount ?: 1) - 1)
                    }
                    MSearchView.SEARCH_END -> {
                        setupTopBarNavigation()
                        contactRecyclerView?.isNestedScrollingEnabled = true
                    }
                }
            }
            override fun onSearchTextChanged(s: String?) {
                searchString = s ?: ""
                searchListByQuery(searchString)
            }
        })

        contactRecyclerView = findViewById<MyRecyclerView>(R.id.contactRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@ContactPickerActivity)
            setHasFixedSize(false)
        }
        contactAdapter = ContactPickerAdapter(this)
        contactRecyclerView?.adapter = contactAdapter

        contactRecyclerView?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val visible = lm.childCount
                val total = lm.itemCount
                val first = lm.findFirstVisibleItemPosition()
                if (!isCallLogMode && !isLoadingMore && hasMoreContacts && searchString.isEmpty()) {
                    if (visible + first >= total - 5) loadMoreContacts()
                }
            }
        })

        contactAdapter?.setListener(object : ContactPickerAdapter.ContactPickerAdapterListener {
            override fun onContactToggled(position: Int, isSelected: Boolean) {
                if (position !in filteredContacts.indices) return
                val contact = filteredContacts[position]
                val idx = allContacts.indexOfFirst {
                    if (contact.contactId.isEmpty()) it.phoneNumber == contact.phoneNumber
                    else it.contactId == contact.contactId && it.phoneNumber == contact.phoneNumber
                }
                if (idx >= 0) {
                    if (isSelected) selectedPositions.add(idx) else selectedPositions.remove(idx)
                }
            }
        })

        contactPickerFilterBar = findViewById(R.id.contact_picker_filter_bar)
        val filterBar = contactPickerFilterBar as? ViewGroup
        callLogPlaceholder = findViewById(R.id.call_log_placeholder)
        setupFilterBarScrollBehavior()
        if (filterBar != null && filterBar.childCount >= 2) {
            filterCallLog = filterBar.getChildAt(0) as? MyTextView
            filterContacts = filterBar.getChildAt(1) as? MyTextView
        } else {
            filterCallLog = findViewById(R.id.filter_call_log)
            filterContacts = findViewById(R.id.filter_contacts)
        }

        filterCallLog?.let { callLogTab ->
            callLogTab.isClickable = true
            callLogTab.isFocusable = true
            callLogTab.setOnClickListener {
                if (!isCallLogMode) {
                    isCallLogMode = true
                    updateFilterBar()
                    if (checkCallLogPermission()) loadCallLog()
                }
            }
        }
        filterContacts?.let { contactsTab ->
            contactsTab.isClickable = true
            contactsTab.isFocusable = true
            contactsTab.setOnClickListener {
                if (isCallLogMode) {
                    isCallLogMode = false
                    updateFilterBar()
                    if (checkContactsPermission()) loadContacts()
                }
            }
        }
        updateFilterBar()
    }

    private fun setupTopBarNavigation() {
        blurAppBarLayout?.toolbar?.apply {
            val textColor = getProperTextColor()
            navigationIcon = resources.getColoredDrawableWithColor(
                this@ContactPickerActivity,
                com.android.common.R.drawable.ic_cmn_arrow_left_fill,
                textColor
            )
            setNavigationContentDescription(com.goodwy.commons.R.string.back)
            setNavigationOnClickListener {
                finish()
            }
        }
        blurAppBarLayout?.titleView?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = (64 * resources.displayMetrics.density).toInt()
        }
    }

    private fun hideTopBarNavigation() {
        blurAppBarLayout?.toolbar?.apply {
            navigationIcon = null
            setNavigationOnClickListener(null)
        }
    }

    private fun updateFilterBar() {
        val textColor = getProperTextColor()
        val primaryColor = getProperPrimaryColor()
        if (isCallLogMode) {
            filterCallLog?.setTextColor(primaryColor)
            filterContacts?.setTextColor(textColor)
        } else {
            filterCallLog?.setTextColor(textColor)
            filterContacts?.setTextColor(primaryColor)
        }
    }

    /** Hides filter bar when app bar is scrolled (top item going up); shows when app bar is back at original (expanded). */
    private fun setupFilterBarScrollBehavior() {
        val bar = blurAppBarLayout ?: return
        val filterBar = contactPickerFilterBar ?: return
        bar.addOnOffsetChangedListener(object : AppBarLayout.OnOffsetChangedListener {
            override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
                val expanded = verticalOffset >= -dp(8)
                filterBar.visibility = if (expanded) View.VISIBLE else View.GONE
            }
        })
    }

    private fun searchListByQuery(s: String) {
        searchString = s
        if (s.trim().isEmpty()) {
            filteredContacts.clear()
            filteredContacts.addAll(allContacts)
            updateAdapterWithFilteredContacts()
            return
        }
        val query = s.lowercase().trim()
        filteredContacts.clear()
        for (contact in allContacts) {
            val matches = (contact.name?.lowercase()?.contains(query) == true) ||
                (contact.phoneNumber?.lowercase()?.contains(query) == true) ||
                (contact.address?.lowercase()?.contains(query) == true) ||
                (contact.organizationName?.lowercase()?.contains(query) == true)
            if (matches) filteredContacts.add(contact)
        }
        updateAdapterWithFilteredContacts()
    }

    private fun updateAdapterWithFilteredContacts() {
        val filteredSelected = HashSet<Int>()
        filteredContacts.forEachIndexed { i, contact ->
            val idx = allContacts.indexOfFirst {
                if (contact.contactId.isEmpty()) it.phoneNumber == contact.phoneNumber
                else it.contactId == contact.contactId && it.phoneNumber == contact.phoneNumber
            }
            if (idx >= 0 && selectedPositions.contains(idx)) filteredSelected.add(i)
        }
        contactAdapter?.setItems(filteredContacts, filteredSelected)
    }

    private fun checkContactsPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), PERMISSION_REQUEST_READ_CONTACTS)
            false
        } else true
    }

    private fun checkCallLogPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALL_LOG), PERMISSION_REQUEST_READ_CALL_LOG)
            false
        } else true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_READ_CONTACTS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadContacts()
                } else {
                    Toast.makeText(this, com.goodwy.commons.R.string.no_contacts_permission, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            PERMISSION_REQUEST_READ_CALL_LOG -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadCallLog()
                } else {
                    Toast.makeText(this, R.string.call_log_permission_required, Toast.LENGTH_LONG).show()
                    isCallLogMode = false
                    updateFilterBar()
                }
            }
        }
    }

    private fun loadCallLog() {
        allContacts.clear()
        filteredContacts.clear()
        selectedPositions.clear()
        callLogPlaceholder?.visibility = View.GONE
        contactRecyclerView?.visibility = View.VISIBLE

        val alreadySelected = intent?.getParcelableArrayListExtra<Contact>(EXTRA_ALREADY_SELECTED_CONTACTS) ?: arrayListOf()
        val alreadyNumbers = alreadySelected.map { normalizePhoneNumber(it.phoneNumber) }.toSet()

        Thread {
            try {
                val projection = arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.DATE
                )
                var cursor: android.database.Cursor? = null
                try {
                    cursor = contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        null,
                        null,
                        "${CallLog.Calls.DATE} DESC"
                    )
                } catch (_: SecurityException) {
                    runOnUiThread {
                        callLogPlaceholder?.visibility = View.VISIBLE
                        contactRecyclerView?.visibility = View.GONE
                        contactAdapter?.setItems(emptyList(), emptySet())
                    }
                    return@Thread
                }

                val list = ArrayList<Contact>()
                val seenNumbers = HashSet<String>()
                var nameCol = -1
                cursor?.use { c ->
                    nameCol = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val numberCol = c.getColumnIndex(CallLog.Calls.NUMBER)
                    if (numberCol < 0) return@use
                    var count = 0
                    while (c.moveToNext() && count < CALL_LOG_LIMIT) {
                        val number = c.getString(numberCol) ?: continue
                        if (number.isBlank()) continue
                        val normalized = normalizePhoneNumber(number)
                        if (seenNumbers.contains(normalized)) continue
                        seenNumbers.add(normalized)
                        var name = number
                        if (nameCol >= 0) {
                            val cached = c.getString(nameCol)
                            if (!cached.isNullOrBlank()) name = cached
                        }
                        if (name == number && ContextCompat.checkSelfPermission(this@ContactPickerActivity, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                            getDisplayNameForNumber(number)?.let { resolvedName ->
                                if (resolvedName.isNotBlank()) name = resolvedName
                            }
                        }
                        list.add(Contact(name = name, contactId = "", phoneNumber = number))
                        count++
                    }
                }

                val selected = HashSet<Int>()
                list.forEachIndexed { index, contact ->
                    if (alreadyNumbers.contains(normalizePhoneNumber(contact.phoneNumber))) {
                        selected.add(index)
                    }
                }
                runOnUiThread {
                    allContacts.clear()
                    allContacts.addAll(list)
                    filteredContacts.clear()
                    if (searchString.trim().isEmpty()) {
                        filteredContacts.addAll(list)
                        contactAdapter?.setItems(list, selected)
                    } else {
                        searchListByQuery(searchString)
                    }
                    selectedPositions.clear()
                    selected.forEach { selectedPositions.add(it) }
                    if (list.isEmpty()) {
                        callLogPlaceholder?.visibility = View.VISIBLE
                        contactRecyclerView?.visibility = View.GONE
                    } else {
                        callLogPlaceholder?.visibility = View.GONE
                        contactRecyclerView?.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    callLogPlaceholder?.visibility = View.VISIBLE
                    contactRecyclerView?.visibility = View.GONE
                    contactAdapter?.setItems(emptyList(), emptySet())
                }
            }
        }.start()
    }

    private fun loadContacts() {
        allContacts.clear()
        filteredContacts.clear()
        selectedPositions.clear()
        addedContactIds.clear()
        hasMoreContacts = false
        contactsCursor?.takeIf { !it.isClosed }?.close()
        contactsCursor = null
        contactAdapter?.setItems(emptyList(), emptySet())
        callLogPlaceholder?.visibility = View.GONE
        contactRecyclerView?.visibility = View.VISIBLE

        val alreadySelected = intent?.getParcelableArrayListExtra<Contact>(EXTRA_ALREADY_SELECTED_CONTACTS) ?: arrayListOf()
        alreadySelectedContactIds.clear()
        alreadySelected.forEach { c ->
            if (c.contactId.isNotEmpty()) {
                val key = contactNumberKey(c.contactId, c.phoneNumber)
                alreadySelectedContactIds.add(key)
            }
        }

        // Use same source as NewConversationActivity: SimpleContactsHelper only includes phone/SIM contacts (excludes hidden-account "Hidden Contact N" entries)
        SimpleContactsHelper(this).getAvailableContacts(false) { simpleContacts ->
            val contactList = ArrayList<Contact>()
            val selected = HashSet<Int>()
            var index = 0
            for (sc in simpleContacts) {
                val contactIdStr = sc.contactId.toString()
                val name = sc.name
                val org = sc.company ?: ""
                if (sc.phoneNumbers.isEmpty()) {
                    val key = contactNumberKey(contactIdStr, "")
                    contactList.add(Contact(name, contactIdStr, -1, "", "", org))
                    if (alreadySelectedContactIds.contains(key)) selected.add(index)
                    index++
                } else {
                    for (pn in sc.phoneNumbers) {
                        val key = contactNumberKey(contactIdStr, pn.value)
                        contactList.add(Contact(name, contactIdStr, -1, pn.value, "", org))
                        if (alreadySelectedContactIds.contains(key)) selected.add(index)
                        index++
                    }
                }
            }
            contactList.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            runOnUiThread {
                allContacts.clear()
                allContacts.addAll(contactList)
                selectedPositions.clear()
                selected.forEach { selectedPositions.add(it) }
                filteredContacts.clear()
                if (searchString.trim().isEmpty()) {
                    filteredContacts.addAll(contactList)
                    contactAdapter?.setItems(contactList, selected)
                } else {
                    searchListByQuery(searchString)
                }
            }
        }
    }

    private fun loadMoreContacts() {
        // No-op: contacts are loaded all at once via SimpleContactsHelper (hasMoreContacts = false)
    }

    private fun contactNumberKey(contactId: String, phoneNumber: String): String {
        return "$contactId|${normalizePhoneNumber(phoneNumber)}"
    }

    /** Resolves display name from Contacts by phone number; returns null if not found or on error. */
    private fun getDisplayNameForNumber(number: String): String? {
        if (number.isBlank()) return null
        var cursor: android.database.Cursor? = null
        try {
            val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            cursor = contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) {
                val idx = cursor.getColumnIndex(PhoneLookup.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
            // ignore
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun getAllPhoneNumbersForContact(contactId: String): List<String> {
        val numbers = ArrayList<String>()
        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC, ${ContactsContract.CommonDataKinds.Phone._ID} ASC"
            )
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    if (!number.isNullOrEmpty()) numbers.add(number)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return numbers
    }

    private fun getAddressForContact(contactId: String): String? {
        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                    ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                    ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                    ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                    ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                    ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY
                ),
                "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
                arrayOf(contactId),
                "${ContactsContract.CommonDataKinds.StructuredPostal.IS_PRIMARY} DESC"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
                if (idx >= 0) {
                    val addr = cursor.getString(idx)
                    if (!addr.isNullOrEmpty()) return addr
                }
                val parts = listOf(
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.STREET)),
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.CITY)),
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.REGION)),
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE)),
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY))
                ).filter { !it.isNullOrEmpty() }
                return parts.joinToString(", ").trim().trimEnd(',')
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return ""
    }

    private fun getOrganizationNameForContact(contactId: String): String? {
        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Organization.COMPANY,
                    ContactsContract.Data.IS_PRIMARY
                ),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
                "${ContactsContract.Data.IS_PRIMARY} DESC"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)
                if (idx >= 0) {
                    val company = cursor.getString(idx)
                    if (!company.isNullOrEmpty()) return company
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return ""
    }

    private fun returnSelectedContacts() {
        val selectedContacts = ArrayList<Contact>()
        for (pos in selectedPositions.sorted()) {
            if (pos in allContacts.indices) selectedContacts.add(allContacts[pos])
        }

        val contactNumbersCount = HashMap<String, HashSet<String>>()
        allContacts.forEach { contact ->
            if (contact.contactId.isNotEmpty() && contact.phoneNumber.isNotEmpty()) {
                val normalized = normalizePhoneNumber(contact.phoneNumber)
                if (normalized.isNotEmpty()) {
                    contactNumbersCount.getOrPut(contact.contactId) { HashSet() }.add(normalized)
                }
            }
        }
        val multiNumberContactIds = contactNumbersCount
            .filterValues { it.size > 1 }
            .keys

        val displayTexts = ArrayList<String>()
        val normalizedNumbers = ArrayList<String>()
        val usedDisplayTexts = HashSet<String>()
        selectedContacts.forEach { c ->
            val normalizedNumber = normalizePhoneNumber(c.phoneNumber)
            val baseName = if (c.name.isNotEmpty()) c.name else c.phoneNumber
            val shouldShowNumberInDisplay = c.contactId.isNotEmpty() && multiNumberContactIds.contains(c.contactId)
            val baseDisplayText = if (shouldShowNumberInDisplay && c.phoneNumber.isNotEmpty()) {
                "$baseName (${c.phoneNumber})"
            } else {
                baseName
            }
            val uniqueDisplayText = if (!usedDisplayTexts.contains(baseDisplayText)) {
                baseDisplayText
            } else {
                // Keep chip labels unique when multiple selected numbers share the same contact name.
                if (c.phoneNumber.isNotEmpty()) "$baseDisplayText (${c.phoneNumber})" else "$baseDisplayText (${normalizedNumber})"
            }

            usedDisplayTexts.add(uniqueDisplayText)
            displayTexts.add(uniqueDisplayText)
            normalizedNumbers.add(normalizedNumber)
        }

        val resultIntent = Intent().apply {
            putParcelableArrayListExtra(EXTRA_SELECTED_CONTACTS, selectedContacts)
            putStringArrayListExtra(EXTRA_SELECTED_DISPLAY_TEXTS, displayTexts)
            putStringArrayListExtra(EXTRA_SELECTED_PHONE_NUMBERS, normalizedNumbers)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun normalizePhoneNumber(phone: String): String {
        return phone.filter { it.isDigit() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
