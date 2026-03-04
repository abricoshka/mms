package com.goodwy.commons.views

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.goodwy.commons.R
import com.goodwy.commons.helpers.AvatarResolver
import com.goodwy.commons.helpers.AvatarSource
import com.goodwy.commons.helpers.MonogramGenerator
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/**
 * Custom view for displaying contact avatars with support for multiple sources.
 * Extends FrameLayout and inflates view_contact_avatar.xml layout.
 * 
 * This view handles:
 * - Contact Poster subject mask images
 * - Contact photos
 * - Monogram avatars with initials and gradient backgrounds
 * 
 * Features:
 * - Circular clipping for iOS-like appearance
 * - Elevation and shadow for depth
 * - Efficient image loading with thumbnail scaling
 * - Memory-safe with proper cleanup
 */
class ContactAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // View references
    private val avatarImage: ImageView
    private val avatarInitials: TextView

    // Current Glide request for memory leak prevention
    private var currentImageRequest: Any? = null

    // Thumbnail size for performance optimization
    private val THUMBNAIL_SIZE = 200

    init {
        // Inflate the layout
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.view_contact_avatar, this, true)

        // Get view references
        avatarImage = view.findViewById(R.id.avatarImage)
        avatarInitials = view.findViewById(R.id.avatarInitials)

        // Setup circular clipping for the container
        setupCircularClipping()
        
        // Add elevation and shadow for depth (iOS 26 style)
        setupElevationAndShadow()
    }

    /**
     * Sets up circular clipping for the avatar view.
     * Uses ViewOutlineProvider for efficient circular clipping.
     * The outline will be updated when the view size changes.
     */
    private fun setupCircularClipping() {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                val size = minOf(view.width, view.height)
                outline.setOval(0, 0, size, size)
            }
        }
    }

    /**
     * Sets up elevation and shadow for iOS 26-like depth effect.
     */
    private fun setupElevationAndShadow() {
        // Add elevation for shadow effect
        elevation = 4f
        
        // Enable hardware layer for better shadow rendering
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Binds the avatar view with an AvatarSource.
     * 
     * Handles three types of avatar sources:
     * - Poster: Loads subject mask image with circleCrop
     * - Photo: Loads contact photo with circleCrop
     * - Monogram: Shows initials with gradient background
     * 
     * @param source The AvatarSource to display
     */
    fun bind(source: AvatarSource) {
        // Clear previous image request to prevent memory leaks
        clearImageRequest()

        when (source) {
            is AvatarSource.Poster -> bindPoster(source.subjectUri)
            is AvatarSource.Photo -> bindPhoto(source)
            is AvatarSource.Drawable -> bindDrawable(source.drawableResId, source.tintColor, source.backgroundColor)
            is AvatarSource.Monogram -> bindMonogram(
                source.initials,
                source.gradientColors,
                source.drawableIndex
            )
        }
    }

    /**
     * Binds Poster avatar source.
     * Loads the subject mask URI with circleCrop transformation.
     * 
     * PERFORMANCE: Uses thumbnail scaling to prevent full-size bitmap decoding.
     * - .override(THUMBNAIL_SIZE, THUMBNAIL_SIZE) ensures Glide decodes only 200x200
     * - No full-size bitmap is ever loaded into memory
     * - Disk cache stores the thumbnail, not the full image
     * 
     * @param subjectUri The URI of the subject mask image
     */
    private fun bindPoster(subjectUri: String) {
        // Show image, hide initials
        avatarImage.isVisible = true
        avatarInitials.isVisible = false

        // Clear any gradient background from container
        background = null
        // Clear background from image view
        avatarImage.background = null

        // Parse URI
        val imageUri = try {
            Uri.parse(subjectUri)
        } catch (e: Exception) {
            null
        }

        if (imageUri != null) {
            currentImageRequest = imageUri
            
            // Load image with Glide, using thumbnail scaling for performance
            // CRITICAL: .override() ensures no full-size bitmap decoding
            // Glide will decode and resize to THUMBNAIL_SIZE before loading into memory
            Glide.with(this)
                .load(imageUri)
                .apply(
                    RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .circleCrop()
                        .override(THUMBNAIL_SIZE, THUMBNAIL_SIZE) // Prevents full-size decoding
                        .error(null)
                )
                .into(avatarImage)
        } else {
            // Invalid URI - clear image
            avatarImage.setImageDrawable(null)
        }
    }

    /**
     * Binds Photo avatar source.
     * Loads the contact photo URI with circleCrop transformation.
     * 
     * PERFORMANCE: Uses thumbnail scaling to prevent full-size bitmap decoding.
     * - .override(THUMBNAIL_SIZE, THUMBNAIL_SIZE) ensures Glide decodes only 200x200
     * - No full-size bitmap is ever loaded into memory
     * - Disk cache stores the thumbnail, not the full image
     * 
     * @param photoUri The URI of the contact photo
     */
    private fun bindPhoto(source: AvatarSource.Photo) {
        // Show image, hide initials
        avatarImage.isVisible = true
        avatarInitials.isVisible = false

        // Clear any gradient background from container
        background = null
        // Clear background from image view
        avatarImage.background = null

        // Parse URI
        val imageUri = try {
            Uri.parse(source.photoUri)
        } catch (e: Exception) {
            null
        }

        if (imageUri != null) {
            currentImageRequest = imageUri
            
            // Load image with Glide, using thumbnail scaling for performance
            // CRITICAL: .override() ensures no full-size bitmap decoding
            // Glide will decode and resize to THUMBNAIL_SIZE before loading into memory
            Glide.with(this)
                .load(imageUri)
                .apply(
                    RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .circleCrop()
                        .override(THUMBNAIL_SIZE, THUMBNAIL_SIZE) // Prevents full-size decoding
                        .error(null)
                )
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        val fallback = source.fallbackMonogram
                        return if (fallback != null) {
                            bindMonogram(
                                initials = fallback.initials,
                                gradientColors = fallback.gradientColors,
                                drawableIndex = fallback.drawableIndex
                            )
                            true
                        } else {
                            false
                        }
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean = false
                })
                .into(avatarImage)
        } else {
            val fallback = source.fallbackMonogram
            if (fallback != null) {
                bindMonogram(
                    initials = fallback.initials,
                    gradientColors = fallback.gradientColors,
                    drawableIndex = fallback.drawableIndex
                )
            } else {
                // Invalid URI - clear image
                avatarImage.setImageDrawable(null)
            }
        }
    }

    /**
     * Binds Drawable avatar source (e.g. special rows: My Info, Service numbers, Company numbers).
     * Shows a drawable icon with tint on a solid background.
     */
    private fun bindDrawable(drawableResId: Int, tintColor: Int, backgroundColor: Int) {
        avatarImage.isVisible = true
        avatarInitials.isVisible = false
        background = GradientDrawable().apply {
            setColor(backgroundColor)
            shape = GradientDrawable.OVAL
        }
        avatarImage.background = null
        avatarImage.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        avatarImage.scaleType = ImageView.ScaleType.FIT_CENTER
        val size = minOf(width, height).takeIf { it > 0 } ?: (resources.displayMetrics.density * 48f).toInt()
        val inset = (size * 0.2f).toInt().coerceAtLeast(4)
        avatarImage.setPadding(inset, inset, inset, inset)
        avatarImage.setImageResource(drawableResId)
        avatarImage.imageTintList = ColorStateList.valueOf(tintColor)
    }

    /**
     * Binds Monogram avatar source.
     * Shows initials with gradient background.
     * Uses drawable resource if drawableIndex is provided, otherwise falls back to programmatic gradient.
     * 
     * @param initials The initials to display
     * @param gradientColors The list of colors for the gradient background (fallback)
     * @param drawableIndex The index (0-26) for the avatar gradient drawable resource
     */
    private fun bindMonogram(initials: String, gradientColors: List<Int>, drawableIndex: Int? = null) {
        val monogramChar = extractFirstMonogramCharacter(initials)

        // Use drawable resource if drawableIndex is provided, otherwise use programmatic gradient
        if (drawableIndex != null) {
            // Convert drawableIndex to resource number (1-27)
            // drawableIndex 0 -> contact_avatar_bg_1, drawableIndex 1 -> contact_avatar_bg_2, etc.
            val resourceNumber = (drawableIndex % 27) + 1
            val resourceName = "contact_avatar_bg_$resourceNumber"
            
            val resourceId = context.resources.getIdentifier(resourceName, "drawable", context.packageName)
            
            if (resourceId != 0) {
                // Use the drawable resource
                @SuppressLint("UseCompatLoadingForDrawables")
                background = context.getDrawable(resourceId)
            } else {
                // Fallback to contact_avatar_bg_1 if resource not found
                @SuppressLint("UseCompatLoadingForDrawables")
                background = context.getDrawable(com.goodwy.commons.R.drawable.contact_avatar_bg_1)
            }
        } else {
            // Fallback: Create and set gradient background programmatically
            // The gradient will be clipped to circular shape by clipToOutline
            val gradientDrawable = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setColors(gradientColors.toIntArray())
            }
            
            // Set gradient as background for the container (will be circular due to clipToOutline)
            background = gradientDrawable
        }
        
        // Clear any background from initials TextView to avoid double rendering
        avatarInitials.background = null

        if (monogramChar != null) {
            // Show initials when a valid first letter exists.
            avatarImage.isVisible = false
            avatarInitials.isVisible = true

            avatarInitials.gravity = android.view.Gravity.CENTER
            avatarInitials.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            avatarInitials.text = monogramChar

            // Clear previously used default icon state.
            avatarImage.setImageDrawable(null)
            avatarImage.imageTintList = null
        } else {
            // Default avatar style: show person icon when initials are missing.
            avatarImage.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            avatarImage.isVisible = true
            avatarInitials.isVisible = false
            // FIT_CENTER allows the vector icon to scale up to available space.
            avatarImage.scaleType = ImageView.ScaleType.FIT_CENTER
            val iconInset = (minOf(width, height).takeIf { it > 0 } ?: resources.displayMetrics.density.times(150f).toInt()) * 0.04f
            avatarImage.setPadding(iconInset.toInt(), iconInset.toInt(), iconInset.toInt(), iconInset.toInt())
            avatarImage.setImageResource(R.drawable.ic_person)
            avatarImage.imageTintList = ColorStateList.valueOf(Color.WHITE)
            // Show only icon over monogram-like gradient background.
            avatarImage.background = null
        }
        
        // Update text size to scale with avatar size
        // Use post to ensure view has been measured
        post {
            updateMonogramTextSize()
        }
    }

    private fun extractFirstMonogramCharacter(value: String): String? {
        val firstChar = value.trim().firstOrNull { it.isLetter() } ?: return null
        return firstChar.uppercaseChar().toString()
    }
    
    /**
     * Updates the monogram text size based on the view's actual size.
     * This ensures the text scales proportionally with the avatar size,
     * fixing centering issues when avatar size changes.
     */
    private fun updateMonogramTextSize() {
        if (!avatarInitials.isVisible) return
        
        val size = minOf(width, height)
        if (size <= 0) return


        // Calculate text size as 50% of the view size (similar to canvas-based approach)
        // This ensures the letter scales proportionally with the avatar
        val textSizePx = size * 0.39f

        // Set text size in pixels for precise control
        avatarInitials.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
    }

    /**
     * Clears the current Glide image request to prevent memory leaks.
     */
    private fun clearImageRequest() {
        if (currentImageRequest != null) {
            Glide.with(this).clear(avatarImage)
            currentImageRequest = null
        }
    }

    /**
     * Clean up when view is detached from window.
     * Prevents memory leaks by clearing Glide requests.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearImageRequest()
    }

    /**
     * Updates the outline when view size changes.
     * Ensures circular clipping remains correct after layout changes.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Invalidate outline to update circular clipping
        invalidateOutline()
        // Update monogram text size if visible (avatar size may have changed)
        if (avatarInitials.isVisible) {
            updateMonogramTextSize()
        }
    }
}
