package com.android.mms.adapters

import android.text.TextUtils
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.databinding.ItemContactWithNumberBinding
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.AvatarResolver
import com.goodwy.commons.models.SimpleContact
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.views.MyRecyclerView
import com.android.mms.activities.SimpleActivity

// Data class to represent a contact with a specific phone number
data class ContactPhonePair(
    val contact: SimpleContact,
    val phoneNumber: PhoneNumber
)

class ContactsAdapter(
    activity: SimpleActivity, var contactPhonePairs: ArrayList<ContactPhonePair>, recyclerView: MyRecyclerView, itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {
    private var fontSize = activity.getTextSize()

    override fun getActionMenuId() = 0

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {}

    override fun getSelectableItemCount() = contactPhonePairs.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = contactPhonePairs.getOrNull(position)?.contact?.rawId

    override fun getItemKeyPosition(key: Int) = contactPhonePairs.indexOfFirst { it.contact.rawId == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactWithNumberBinding.inflate(layoutInflater, parent, false)
        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contactPhonePair = contactPhonePairs[position]
        holder.bindView(contactPhonePair, allowSingleClick = true, allowLongClick = false) { itemView, _ ->
            setupView(itemView, contactPhonePair)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = contactPhonePairs.size

    fun updateContacts(newContactPhonePairs: ArrayList<ContactPhonePair>) {
        val oldHashCode = contactPhonePairs.hashCode()
        val newHashCode = newContactPhonePairs.hashCode()
        if (newHashCode != oldHashCode) {
            contactPhonePairs = newContactPhonePairs
            notifyDataSetChanged()
        }
    }

    private fun setupView(view: View, contactPhonePair: ContactPhonePair) {
        val contact = contactPhonePair.contact
        val phoneNumber = contactPhonePair.phoneNumber
        
        ItemContactWithNumberBinding.bind(view).apply {
            divider.apply {
                beInvisibleIf(getLastItem() == contactPhonePair || !baseConfig.useDividers)
                setBackgroundColor(textColor)
            }

            // Display contact name
            itemContactName.apply {
                text = contact.name
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
            }

            // Display phone number
            itemContactNumber.apply {
                text = phoneNumber.value
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * 0.8f)
            }

            itemContactImage.beGoneIf(!baseConfig.showContactThumbnails)
            itemContactImage.bind(
                AvatarResolver.resolve(
                    contactId = contact.rawId.toLong(),
                    posterConfig = null,
                    contactPhotoUri = contact.photoUri.takeIf { it.isNotEmpty() },
                    contactName = contact.name,
                    styleConfig = null
                )
            )
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            val binding = ItemContactWithNumberBinding.bind(holder.itemView)
            Glide.with(activity).clear(binding.itemContactImage)
        }
    }

    private fun getLastItem() = contactPhonePairs.last()
}
