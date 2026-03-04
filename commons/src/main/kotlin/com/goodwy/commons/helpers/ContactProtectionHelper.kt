package com.goodwy.commons.helpers

import android.content.Context
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import androidx.core.os.bundleOf

/**
 * Result of [unlockAllWithPin]: count of unlocked raw contacts and their IDs (when available).
 * Used so callers can ensure the contact list refresh reflects the newly unlocked contacts.
 */
data class UnlockResult(
    val count: Int,
    val rawContactIds: LongArray?
) {
    override fun equals(other: Any?): Boolean =
        (other as? UnlockResult)?.let { count == it.count && rawContactIds.contentEquals(it.rawContactIds) } ?: false
    override fun hashCode(): Int = 31 * count + (rawContactIds?.contentHashCode() ?: 0)
}

/**
 * Test helper for ContactProvider protection API (set_protected / unprotect).
 * Uses PIN "1080" for testing. Tracks protected raw contact IDs in SharedPreferences
 * so we can toggle unprotect after the contact is hidden from queries.
 */
object ContactProtectionHelper {

    private const val TAG = "ContactProtection"

    private const val PREFS_NAME = "contact_protection_test"
    private const val KEY_PROTECTED_RAW_IDS = "protected_raw_ids"
    private const val TEST_PIN = "1080"

    /** True after [unlockAllWithPin], false after [lock] or app start. Used to avoid showing "Unprotect" for reused raw contact IDs. */
    @Volatile
    private var unlockedInSession: Boolean = false

    /**
     * The PIN used for the most recent successful [unlockAllWithPin] call, or null when locked.
     * Kept so background threads can re-call unlock_all_with_pin on their own Binder connection
     * if that turns out to be necessary.
     */
    @Volatile
    private var sessionPin: String? = null

    /**
     * Raw contact IDs returned by the most recent [unlockAllWithPin] call.
     * Used by [ContactsHelper.getDeviceContacts] to load unlocked contacts directly from
     * [ContactsContract.Contacts.CONTENT_URI], which respects the unlock state, when
     * [ContactsContract.Data.CONTENT_URI] does not (provider limitation).
     */
    @Volatile
    private var unlockedRawContactIds: LongArray? = null

    fun getUnlockedRawContactIds(): LongArray? = unlockedRawContactIds

    private fun getProtectedIds(context: Context): MutableSet<Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_PROTECTED_RAW_IDS, null) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    private fun setProtectedIds(context: Context, ids: Set<Int>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PROTECTED_RAW_IDS, ids.map { it.toString() }.toSet())
            .apply()
    }

    /**
     * Unprotect the contact (when already protected). Use from list/unlock view long-press menu.
     * For "Protect", use [protectContact] with PIN from dialog.
     */
    fun unprotectContact(context: Context, rawContactId: Int): Boolean {
        val protected = getProtectedIds(context)
        if (!protected.contains(rawContactId)) return false
        Log.d(TAG, "unprotect from unlock view (list): rawContactId=$rawContactId -> calling provider unprotect")
        unprotect(context, rawContactId)
        protected.remove(rawContactId)
        setProtectedIds(context, protected)
        // Remove from the in-memory unlocked set so the supplementary Contacts.CONTENT_URI
        // load in getDeviceContacts doesn't re-add this contact on the next refresh.
        val currentIds = unlockedRawContactIds
        if (currentIds != null) {
            unlockedRawContactIds = currentIds.filter { it != rawContactId.toLong() }.toLongArray()
        }
        Log.d(TAG, "unprotect from unlock view: done, removed from tracking set and unlockedRawContactIds")
        return true
    }

    /**
     * Protect the raw contact with the given PIN. Call after user enters PIN in dialog.
     * Also adds to app's tracking set so "Unprotect" is available.
     */
    fun protectContact(context: Context, rawContactId: Int, pin: String) {
        Log.d(TAG, "protecting a contact with pin: rawContactId=$rawContactId pinLength=${pin.length}")
        setProtected(context, rawContactId, pin)
        val protected = getProtectedIds(context)
        protected.add(rawContactId)
        setProtectedIds(context, protected)
        Log.d(TAG, "protecting a contact with pin: done, added to tracking set")
    }

    /**
     * Protect multiple raw contacts in a single provider call using `set_protected_many`.
     * Updates the app's tracking set for all IDs.
     */
    fun protectMany(context: Context, rawContactIds: List<Int>, pin: String) {
        val trimmedPin = pin.trim()
        if (trimmedPin.isEmpty() || rawContactIds.isEmpty()) return
        Log.d(TAG, "set_protected_many: rawContactIds=$rawContactIds pinLength=${trimmedPin.length}")
        try {
            val extras = bundleOf(
                "raw_contact_ids" to rawContactIds.map { it.toLong() }.toLongArray(),
                "pin" to trimmedPin
            )
            context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "set_protected_many",
                null,
                extras
            )
            Log.d(TAG, "set_protected_many returned (no exception)")
        } catch (e: Exception) {
            Log.e(TAG, "set_protected_many failed", e)
            throw e
        }
        val protected = getProtectedIds(context)
        protected.addAll(rawContactIds)
        setProtectedIds(context, protected)
        Log.d(TAG, "set_protected_many: tracking set updated for ${rawContactIds.size} ids")
    }

    /**
     * Unprotect multiple raw contacts in a single provider call using `unprotect_many`.
     * Updates the app's tracking set and in-memory unlocked list for all IDs.
     */
    fun unprotectMany(context: Context, rawContactIds: List<Int>) {
        if (rawContactIds.isEmpty()) return
        Log.d(TAG, "unprotect_many: rawContactIds=$rawContactIds")
        try {
            val extras = bundleOf(
                "raw_contact_ids" to rawContactIds.map { it.toLong() }.toLongArray()
            )
            context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "unprotect_many",
                null,
                extras
            )
            Log.d(TAG, "unprotect_many returned (no exception)")
        } catch (e: Exception) {
            Log.e(TAG, "unprotect_many failed", e)
            throw e
        }
        val protected = getProtectedIds(context)
        rawContactIds.forEach { protected.remove(it) }
        setProtectedIds(context, protected)
        // Remove from the in-memory unlocked set so the supplementary load doesn't re-add them
        val currentIds = unlockedRawContactIds
        if (currentIds != null) {
            val removeSet = rawContactIds.map { it.toLong() }.toSet()
            unlockedRawContactIds = currentIds.filter { it !in removeSet }.toLongArray()
        }
        Log.d(TAG, "unprotect_many: tracking set and unlockedRawContactIds updated for ${rawContactIds.size} ids")
    }

    fun setProtected(context: Context, rawContactId: Int, pin: String) {
        val extras = bundleOf("pin" to pin)
        Log.d(TAG, "set_protected calling provider rawContactId=$rawContactId pinLength=${pin.length} authority=${ContactsContract.AUTHORITY_URI}")
        try {
            context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "set_protected",
                rawContactId.toString(),
                extras
            )
            Log.d(TAG, "set_protected returned (no exception)")
        } catch (e: Exception) {
            Log.e(TAG, "set_protected failed rawContactId=$rawContactId", e)
            throw e
        }
    }

    fun unprotect(context: Context, rawContactId: Int) {
        Log.d(TAG, "unprotect calling provider rawContactId=$rawContactId authority=${ContactsContract.AUTHORITY_URI}")
        try {
            val extras = bundleOf("raw_contact_id" to rawContactId.toLong())
            context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "unprotect",
                rawContactId.toString(),
                extras
            )
            Log.d(TAG, "unprotect returned (no exception)")
        } catch (e: Exception) {
            Log.e(TAG, "unprotect failed rawContactId=$rawContactId", e)
            throw e
        }
    }

    /** Whether we consider this raw contact protected (from our test tracking). */
    fun isProtected(context: Context, rawContactId: Int): Boolean {
        return getProtectedIds(context).contains(rawContactId)
    }

    /** Call when contact list is received and we are locked. Removes any listed raw contact IDs from the tracking set so reused IDs (e.g. new contacts) don't show "Unprotect". */
    fun removeTrackingIdsThatAppearInList(context: Context, rawContactIdsInList: List<Int>) {
        if (unlockedInSession) return
        val protected = getProtectedIds(context)
        val idsToRemove = rawContactIdsInList.filter { protected.contains(it) }
        if (idsToRemove.isEmpty()) return
        idsToRemove.forEach { protected.remove(it) }
        setProtectedIds(context, protected)
        Log.d(TAG, "removeTrackingIdsThatAppearInList (locked): removed stale ids $idsToRemove so new/reused contacts show Protect")
    }

    fun isUnlockedInSession(): Boolean = unlockedInSession

    /** Test PIN used for protect/unprotect (for display on detail screen). */
    fun getTestPin(): String = TEST_PIN

    /**
     * Unlock all protected raw contacts that use the given PIN for the calling UID.
     * After this, queries will include those contacts until [lock] is called or the process ends.
     * @return [UnlockResult] with count and unlocked raw_contact_ids (so UI can refresh and display them)
     */
    fun unlockAllWithPin(context: Context, pin: String): UnlockResult {
        val trimmedPin = pin.trim()
        if (trimmedPin.isEmpty()) {
            Log.w(TAG, "unlock_all_with_pin: pin is empty")
            return UnlockResult(0, null)
        }
        unlockedInSession = true
        sessionPin = trimmedPin
        unlockedRawContactIds = null // cleared until the call returns with actual IDs
        Log.d(TAG, "unlock contacts with pin: calling provider pinLength=${trimmedPin.length} authority=${ContactsContract.AUTHORITY_URI}")
        val extras = bundleOf("pin" to trimmedPin)
        try {
            val result = context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "unlock_all_with_pin",
                null,
                extras
            )
            val bundleCount = result?.getInt("unlocked_count", -1) ?: -1
            val rawIds = result?.getLongArray("raw_contact_ids")
            val idsSize = rawIds?.size ?: 0
            // Prefer raw_contact_ids size when unlocked_count is missing/wrong (e.g. Bundle across process boundary)
            val effectiveCount = when {
                bundleCount > 0 -> bundleCount
                idsSize > 0 -> idsSize
                else -> 0
            }
            Log.d(TAG, "unlock_all_with_pin: provider returned unlocked_count=$bundleCount raw_contact_ids=$idsSize -> effectiveCount=$effectiveCount")
            if (effectiveCount == 0) {
                Log.w(TAG, "unlock_all_with_pin: no contacts unlocked - check PIN matches and protected_contact_pins has rows (filter logcat by ProtectedContacts)")
            }
            unlockedRawContactIds = rawIds
            return UnlockResult(effectiveCount, rawIds)
        } catch (e: Exception) {
            Log.e(TAG, "unlock_all_with_pin failed", e)
            throw e
        }
    }

    /**
     * Re-establishes the unlock state on the **current thread's** Binder connection.
     * The provider tracks unlock state per Binder connection rather than per UID, so a
     * background thread that was not the one to call [unlockAllWithPin] must call this
     * before querying the provider, otherwise protected contacts remain hidden.
     *
     * Safe to call even when not unlocked (no-op in that case).
     */
    fun ensureUnlockedForThread(context: Context) {
        val pin = sessionPin ?: return
        if (!unlockedInSession) return
        Log.d(TAG, "ensureUnlockedForThread: re-establishing unlock on background thread (pinLength=${pin.length})")
        try {
            val extras = bundleOf("pin" to pin)
            context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "unlock_all_with_pin",
                null,
                extras
            )
            Log.d(TAG, "ensureUnlockedForThread: done")
        } catch (e: Exception) {
            Log.e(TAG, "ensureUnlockedForThread failed", e)
        }
    }

    /**
     * Clear the calling UID's unlock state. Protected contacts become invisible again
     * until [unlockAllWithPin] is called.
     */
    fun lock(context: Context) {
        unlockedInSession = false
        sessionPin = null
        unlockedRawContactIds = null
        Log.d(TAG, "lock contacts: calling provider authority=${ContactsContract.AUTHORITY_URI}")
        try {
            context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "lock",
                null,
                null
            )
            Log.d(TAG, "lock contacts: provider returned (no exception) -> protected contacts should now be hidden from queries")
        } catch (e: Exception) {
            Log.e(TAG, "lock failed", e)
            throw e
        }
    }
}
