package com.android.mms.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import com.android.mms.extensions.subscriptionManagerCompat
import com.android.mms.models.SIMCard

object FeeInfoUtils {
    private const val TAG = "FeeInfoUtils"

    @SuppressLint("MissingPermission")
    fun getCurrentSimSlotId(
        context: Context,
        availableSIMCards: List<SIMCard>,
        currentSIMCardIndex: Int,
    ): Int? {
        val activeSIMs = context.subscriptionManagerCompat().activeSubscriptionInfoList ?: return null
        if (activeSIMs.isEmpty()) return null

        val defaultSubId = SmsManager.getDefaultSmsSubscriptionId()
        val selectedSubscriptionId = availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
            ?: defaultSubId

        Log.d(
            TAG,
            "getCurrentSimSlotId: currentSIMCardIndex=$currentSIMCardIndex, " +
                "availableSIMCards=${availableSIMCards.map { "${it.id}:${it.subscriptionId}" }}, " +
                "defaultSubId=$defaultSubId, selectedSubscriptionId=$selectedSubscriptionId, " +
                "activeSIMs=${activeSIMs.map { "${it.subscriptionId}->slot${it.simSlotIndex}" }}"
        )

        val resolvedSlotId = activeSIMs.firstOrNull { it.subscriptionId == selectedSubscriptionId }?.simSlotIndex
            ?: activeSIMs.firstOrNull()?.simSlotIndex
            ?: currentSIMCardIndex
        Log.d(TAG, "getCurrentSimSlotId: resolvedSlotId=$resolvedSlotId")
        return resolvedSlotId
    }

    fun getAvailableSmsCountForSlot(context: Context, slotId: Int): Int? {
        return try {
            val allUri = Uri.parse("content://com.android.dialer.feeinfo/fee_info")
            Log.d(TAG, "getAvailableSmsCountForSlot: querying uri=$allUri for slotId=$slotId")
            context.contentResolver.query(allUri, null, null, null, null)?.use { cursor ->
                val slotIdColumn = cursor.getColumnIndex("slot_id")
                val smsColumn = cursor.getColumnIndex("sms")
                if (slotIdColumn == -1 || smsColumn == -1) {
                    Log.d(TAG, "getAvailableSmsCountForSlot: missing columns slot_id/sms")
                    return null
                }

                while (cursor.moveToNext()) {
                    val providerSlotId = cursor.getInt(slotIdColumn)
                    val providerSmsCount = cursor.getInt(smsColumn)
                    Log.d(
                        TAG,
                        "getAvailableSmsCountForSlot: row slot_id=$providerSlotId, sms=$providerSmsCount"
                    )
                    if (providerSlotId == slotId) {
                        Log.d(TAG, "getAvailableSmsCountForSlot: matched slotId=$slotId, sms=$providerSmsCount")
                        return providerSmsCount
                    }
                }
                Log.d(TAG, "getAvailableSmsCountForSlot: no matching row for slotId=$slotId")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAvailableSmsCountForSlot: query failed for slotId=$slotId", e)
            null
        }
    }
}
