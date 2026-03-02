package com.android.mms.receivers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getMyContactsCursor
import com.goodwy.commons.extensions.isNumberBlocked
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.goodwy.commons.extensions.trimToComparableNumber
import com.goodwy.commons.helpers.MyContactsContentProvider
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.SimpleContact
import com.android.mms.extensions.config
import com.android.mms.extensions.getConversations
import com.android.mms.extensions.getNameFromAddress
import com.android.mms.extensions.getNotificationBitmap
import com.android.mms.extensions.getThreadId
import com.android.mms.extensions.isCustomerServiceBlockNumber
import com.android.mms.extensions.insertNewSMS
import com.android.mms.extensions.insertOrUpdateConversation
import com.android.mms.extensions.messagesDB
import com.android.mms.extensions.shouldUnarchive
import com.android.mms.extensions.showReceivedMessageNotification
import com.android.mms.extensions.updateConversationArchivedStatus
import com.android.mms.helpers.ReceiverUtils.isMessageFilteredOut
import com.android.mms.helpers.FEE_SERVICE_NUMBER
import com.android.mms.helpers.refreshConversations
import com.android.mms.helpers.refreshMessages
import com.android.mms.models.Message

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
        private var antiThiefPlayer: MediaPlayer? = null
    }

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        var address = ""
        var body = ""
        var subject = ""
        var date = 0L
        var threadId = 0L
        var status = Telephony.Sms.STATUS_NONE
        val type = Telephony.Sms.MESSAGE_TYPE_INBOX
        val read = 0
        val subscriptionId = intent.getIntExtra("subscription", -1)

        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ensureBackgroundThread {
            messages.forEach {
                address = it.originatingAddress ?: ""
                subject = it.pseudoSubject
                status = it.status
                body += it.messageBody
                date = System.currentTimeMillis()
                threadId = context.getThreadId(address)
            }

            val shouldBlockNextFeeMessage = context.config.blockNextFeeServiceMessage
            val shouldIgnoreFeeServiceMessage = shouldIgnoreFeeServiceMessage(address)
            Log.d(
                TAG,
                "onReceive: address=$address blockNextFeeServiceMessage=$shouldBlockNextFeeMessage shouldIgnoreFeeServiceMessage=$shouldIgnoreFeeServiceMessage"
            )
            if (shouldBlockNextFeeMessage && shouldIgnoreFeeServiceMessage) {
                Log.d(TAG, "onReceive: dropping fee service SMS from address=$address and resetting block flag")
                context.config.blockNextFeeServiceMessage = false
                return@ensureBackgroundThread
            }

            triggerAntiThiefAlarmIfNeeded(context, body)

            if (context.isCustomerServiceBlockNumber(address)) {
                return@ensureBackgroundThread
            }

            if (context.baseConfig.blockUnknownNumbers) {
                val simpleContactsHelper = SimpleContactsHelper(context)
                simpleContactsHelper.exists(address) { exists ->
                    if (exists) {
                        handleMessage(context, address, subject, body, date, read, threadId, type, subscriptionId, status)
                    }
                }
            } else {
                handleMessage(context, address, subject, body, date, read, threadId, type, subscriptionId, status)
            }
        }

        if (context.config.notifyTurnsOnScreen) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakelock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "goodwy.messages:sms.receiver"
            )
            wakelock.acquire(3000)
        }
    }

    private fun handleMessage(
        context: Context,
        address: String,
        subject: String,
        body: String,
        date: Long,
        read: Int,
        threadId: Long,
        type: Int,
        subscriptionId: Int,
        status: Int
    ) {
        if (isMessageFilteredOut(context, body)) {
            return
        }

        val isNumberBlocked = context.isNumberBlocked(address)
        var photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
        var bitmap = context.getNotificationBitmap(photoUri)
        Handler(Looper.getMainLooper()).post {
            val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            ensureBackgroundThread {
                SimpleContactsHelper(context).getAvailableContacts(false) {
                    val privateContacts = MyContactsContentProvider.getSimpleContacts(context, privateCursor)
                    val contacts = ArrayList(it + privateContacts)

                    // Always store the message in the system database, regardless of blocking status
                    val newMessageId = context.insertNewSMS(address, subject, body, date, read, threadId, type, subscriptionId)

                    val conversation = context.getConversations(threadId).firstOrNull() ?: return@getAvailableContacts
                    try {
                        context.insertOrUpdateConversation(conversation)
                    } catch (_: Exception) {
                    }

                    val senderName = context.getNameFromAddress(address, privateCursor)
                    val participant = if (contacts.isNotEmpty()) {
                        val contact = contacts.firstOrNull { it.doesHavePhoneNumber(address) } ?: contacts.firstOrNull { it.phoneNumbers.map { it.value }.any { it == address } }
                        if (contact != null) {
                            val phoneNumber = contact.phoneNumbers.firstOrNull { it.normalizedNumber == address } ?: PhoneNumber(address, 0, "", address)
                            if (photoUri.isEmpty()) photoUri = contact.photoUri
                            if (bitmap == null ) bitmap = context.getNotificationBitmap(photoUri)
                            SimpleContact(0, 0, senderName, photoUri, arrayListOf(phoneNumber), ArrayList(), ArrayList(), contact.company, contact.jobPosition)
                        } else {
                            val phoneNumber = PhoneNumber(address, 0, "", address)
                            SimpleContact(0, 0, senderName, photoUri, arrayListOf(phoneNumber), ArrayList(), ArrayList())
                        }
                    } else {
                        val phoneNumber = PhoneNumber(address, 0, "", address)
                        SimpleContact(0, 0, senderName, photoUri, arrayListOf(phoneNumber), ArrayList(), ArrayList())
                    }

                    val participants = arrayListOf(participant)
                    val messageDate = (date / 1000).toInt()

                    val message =
                        Message(
                            newMessageId,
                            body,
                            type,
                            status,
                            participants,
                            messageDate,
                            false,
                            threadId,
                            false,
                            null,
                            address,
                            senderName,
                            photoUri,
                            subscriptionId
                        )
                    context.messagesDB.insertOrUpdate(message)
                    if (context.shouldUnarchive()) {
                        context.updateConversationArchivedStatus(threadId, false)
                    }
                    refreshMessages()
                    refreshConversations()
                    
                    // Only show notification if number is not blocked, or if blocked numbers are being shown
                    if (!isNumberBlocked || context.config.showBlockedNumbers) {
                        context.showReceivedMessageNotification(newMessageId, address, body, threadId, bitmap, subscriptionId)
                    }
                }
            }
        }
    }

    private fun shouldIgnoreFeeServiceMessage(address: String): Boolean {
        // Normalize to a canonical digit form so variants like "+phone number" and "phone number" match.
        val targetCanonical = address.normalizePhoneNumber().removePrefix("+")
        val feeCanonical = FEE_SERVICE_NUMBER.normalizePhoneNumber().removePrefix("+")
        val targetComparable = targetCanonical.trimToComparableNumber()
        val feeComparable = feeCanonical.trimToComparableNumber()
        val shouldIgnore = targetCanonical == feeCanonical || targetComparable == feeComparable
        Log.d(
            TAG,
            "shouldIgnoreFeeServiceMessage: rawAddress=$address targetCanonical=$targetCanonical feeCanonical=$feeCanonical targetComparable=$targetComparable feeComparable=$feeComparable shouldIgnore=$shouldIgnore"
        )
        return shouldIgnore
    }

    private fun triggerAntiThiefAlarmIfNeeded(context: Context, body: String) {
        val contentResolver = context.contentResolver
        val settingAlarmMessage = Settings.System.getInt(contentResolver, "persist.tx.thief_mode.setting.alarm.message", 0) == 1
        val settingLockScreen = Settings.System.getInt(contentResolver, "persist.tx.thief_mode.setting.lockscreen", 0) == 1
        val settingAlarm = Settings.System.getInt(contentResolver, "persist.tx.thief_mode.setting.alarm", 0) == 1
        Log.d(
            TAG,
            "triggerAntiThiefAlarmIfNeeded: alarmMessage=$settingAlarmMessage lockScreen=$settingLockScreen alarm=$settingAlarm bodyLength=${body.length}"
        )

        if (!(settingAlarmMessage && settingLockScreen && settingAlarm)) {
            Log.d(TAG, "triggerAntiThiefAlarmIfNeeded: skipped because one or more required settings are disabled")
            return
        }

        val settingAlarmMessageText = Settings.System.getString(contentResolver, "persist.tx.thief_mode.setting.alarm.message.text") ?: ""
        val isExactMatch = body == settingAlarmMessageText
        val isTrimmedMatch = body.trim() == settingAlarmMessageText.trim()
        val bodyPreview = body.replace("\n", "\\n")
        val settingPreview = settingAlarmMessageText.replace("\n", "\\n")
        Log.d(
            TAG,
            "triggerAntiThiefAlarmIfNeeded: settingTextLength=${settingAlarmMessageText.length} isBlank=${settingAlarmMessageText.isBlank()} exactMatch=$isExactMatch trimmedMatch=$isTrimmedMatch bodyPreview=$bodyPreview settingPreview=$settingPreview"
        )
        if (settingAlarmMessageText.isBlank() || !isExactMatch || !isTrimmedMatch) {
            Log.d(TAG, "triggerAntiThiefAlarmIfNeeded: skipped because alarm message text is blank or does not exactly match SMS body")
            return
        }

        val ringtoneManager = RingtoneManager(context).apply {
            setType(RingtoneManager.TYPE_ALARM)
        }

        val alarmRingtoneUris = ArrayList<Uri>()
        ringtoneManager.cursor?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < 5) {
                ringtoneManager.getRingtoneUri(cursor.position)?.let { alarmRingtoneUris.add(it) }
                count++
            }
        }
        Log.d(TAG, "triggerAntiThiefAlarmIfNeeded: availableRingtones=${alarmRingtoneUris.size}")

        if (alarmRingtoneUris.isEmpty()) {
            Log.d(TAG, "triggerAntiThiefAlarmIfNeeded: skipped because no alarm ringtones are available")
            return
        }

        val which = Settings.System.getInt(contentResolver, "persist.tx.thief_mode.setting.alarm.ringtone", 2)
        val safeIndex = which.coerceIn(0, alarmRingtoneUris.lastIndex)
        Log.d(TAG, "triggerAntiThiefAlarmIfNeeded: requestedRingtoneIndex=$which safeIndex=$safeIndex")
        val ringtoneUri = alarmRingtoneUris[safeIndex]
        Log.d(TAG, "triggerAntiThiefAlarmIfNeeded: ringtoneUri=$ringtoneUri")

        antiThiefPlayer?.let { existing ->
            if (existing.isPlaying) {
                existing.stop()
            }
            existing.release()
        }

        antiThiefPlayer = MediaPlayer.create(context.applicationContext, ringtoneUri)?.apply {
            isLooping = true
            start()
            Log.d(TAG, "triggerAntiThiefAlarmIfNeeded: anti-thief alarm started")
        }
        if (antiThiefPlayer == null) {
            Log.d(TAG, "triggerAntiThiefAlarmIfNeeded: MediaPlayer.create returned null, alarm not started")
        }
    }
}
