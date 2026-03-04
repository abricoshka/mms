package com.goodwy.commons.helpers

import com.goodwy.commons.models.contacts.AvatarSourceType
import com.goodwy.commons.models.contacts.AvatarStyleConfig
import com.goodwy.commons.models.contacts.ContactPosterConfig

/**
 * Sealed class representing different sources for contact avatars.
 * 
 * This sealed class provides type-safe representation of avatar sources,
 * allowing exhaustive when expressions and clear intent in the codebase.
 */
sealed class AvatarSource {
    /**
     * Avatar source from Contact Poster subject mask image.
     * Used when a custom poster configuration includes a subject mask URI.
     * 
     * @property subjectUri The URI of the subject mask image to display as avatar
     */
    data class Poster(val subjectUri: String) : AvatarSource()

    /**
     * Avatar source from contact photo.
     * Used when the contact has a regular photo available.
     * 
     * @property photoUri The URI of the contact's photo image
     */
    data class Photo(
        val photoUri: String,
        val fallbackMonogram: Monogram? = null
    ) : AvatarSource()

    /**
     * Avatar source from a drawable resource (e.g. for special rows: My Info, Service numbers, Company numbers).
     *
     * @property drawableResId The drawable resource id for the icon
     * @property tintColor The color to tint the drawable (e.g. text color)
     * @property backgroundColor The background color for the avatar circle
     */
    data class Drawable(
        val drawableResId: Int,
        val tintColor: Int,
        val backgroundColor: Int
    ) : AvatarSource()

    /**
     * Avatar source from monogram (initials with gradient background).
     * Used as fallback when no photo or poster subject mask is available.
     * 
     * @property initials The initials to display (e.g., "JS" for "John Smith")
     * @property gradientColors The list of colors for the gradient background (fallback if drawableIndex is null)
     * @property drawableIndex The index (0-26) for the avatar gradient drawable resource (contact_avatar_bg_1 to contact_avatar_bg_27)
     * @property fontFamily The font family name for the monogram text, nullable
     */
    data class Monogram(
        val initials: String,
        val gradientColors: List<Int>,
        val drawableIndex: Int? = null,
        val fontFamily: String? = null
    ) : AvatarSource()
}

/**
 * Resolver class for determining the appropriate avatar source for a contact.
 * 
 * This class implements the priority logic for avatar selection:
 * 1. Contact Poster subject mask (highest priority)
 * 2. Contact photo
 * 3. Monogram with generated initials and gradient (fallback)
 * 
 * The resolver is stateless and thread-safe, making it suitable for use
 * across different parts of the application.
 */
object AvatarResolver {

    /**
     * Resolves the appropriate avatar source for a contact based on available data.
     * 
     * When [styleConfig] is provided, it takes precedence and determines the avatar source type.
     * When [styleConfig] is null, falls back to the default priority logic.
     * 
     * Priority order with styleConfig:
     * 1. If styleConfig.sourceType == POSTER and poster subject exists → return Poster
     * 2. Else if styleConfig.sourceType == PHOTO and customPhotoUri exists → return Photo
     * 3. Else if styleConfig.sourceType == MONOGRAM → generate monogram with style config
     * 
     * Fallback priority order (when styleConfig is null):
     * 1. If posterConfig has a subjectMaskUri → return Poster
     * 2. Else if contactPhotoUri is not null/empty → return Photo
     * 3. Else → generate initials and gradient → return Monogram
     * 
     * @param contactId The unique identifier of the contact (for logging/debugging purposes)
     * @param posterConfig The Contact Poster configuration, if available
     * @param contactPhotoUri The URI of the contact's photo, if available
     * @param contactName The contact's name for generating monogram fallback
     * @param styleConfig The AvatarStyleConfig that determines the avatar source type and styling, nullable
     * @return The resolved AvatarSource based on priority and availability
     */
    fun resolve(
        contactId: Long,
        posterConfig: ContactPosterConfig?,
        contactPhotoUri: String?,
        contactName: String,
        styleConfig: AvatarStyleConfig?
    ): AvatarSource {
        // Decision 1: If styleConfig is provided, use it to determine the avatar source type
        if (styleConfig != null) {
            // Decision 1a: If styleConfig specifies POSTER and poster subject exists, use it
            // This respects the user's explicit choice to use the poster subject mask
            if (styleConfig.sourceType == AvatarSourceType.POSTER) {
                val subjectMaskUri = posterConfig?.subjectMaskUri
                if (subjectMaskUri != null && subjectMaskUri.isNotEmpty()) {
                    return AvatarSource.Poster(subjectUri = subjectMaskUri)
                }
            }

            // Decision 1b: If styleConfig specifies PHOTO and customPhotoUri exists, use it
            // This allows using a custom photo URI from the style configuration
            if (styleConfig.sourceType == AvatarSourceType.PHOTO) {
                val photoUri = styleConfig.customPhotoUri ?: contactPhotoUri
                if (photoUri != null && photoUri.isNotEmpty()) {
                    return AvatarSource.Photo(photoUri = photoUri)
                }
            }

            // Decision 1c: If styleConfig specifies MONOGRAM, generate monogram with style config
            // This uses the configured background colors and font family from the style config
            if (styleConfig.sourceType == AvatarSourceType.MONOGRAM) {
                // Generate initials from contact name
                val initials = MonogramGenerator.generateInitials(contactName)
                
                // Calculate drawable index from name hash (0-26 for contact_avatar_bg_1 to contact_avatar_bg_27)
                val drawableIndex = kotlin.math.abs(contactName.hashCode()) % 27
                
                // Use background colors from style config if available, otherwise fallback to generated colors
                val gradientColors = styleConfig.backgroundColors 
                    ?: posterConfig?.gradientColors
                    ?: MonogramGenerator.generateGradientColors(contactName)
                
                // Apply font family from style config
                return AvatarSource.Monogram(
                    initials = initials,
                    gradientColors = gradientColors,
                    drawableIndex = drawableIndex,
                    fontFamily = styleConfig.fontFamily
                )
            }
        }

        // Decision 2: Fallback to default priority logic when no styleConfig is provided
        // This maintains backward compatibility with existing code that doesn't use styleConfig
        
        // Step 2a: Check if Contact Poster subject mask is available
        // This has the highest priority as it represents a custom poster configuration
        val subjectMaskUri = posterConfig?.subjectMaskUri
        if (subjectMaskUri != null && subjectMaskUri.isNotEmpty()) {
            return AvatarSource.Poster(subjectUri = subjectMaskUri)
        }

        // Step 2b: Check if contact photo is available
        // This is the second priority - use the contact's actual photo if available
        if (contactPhotoUri != null && contactPhotoUri.isNotEmpty()) {
            return AvatarSource.Photo(photoUri = contactPhotoUri)
        }

        // Step 2c: Fallback to monogram
        // Generate initials and gradient from the contact's name
        // This ensures every contact has a visual representation
        val initials = MonogramGenerator.generateInitials(contactName)
        
        // Calculate drawable index from name hash (0-26 for contact_avatar_bg_1 to contact_avatar_bg_27)
        val drawableIndex = kotlin.math.abs(contactName.hashCode()) % 27
        
        // Use gradient colors from poster config if available, otherwise generate from name
        // This maintains consistency with the poster configuration when available
        val gradientColors = posterConfig?.gradientColors 
            ?: MonogramGenerator.generateGradientColors(contactName)
        
        return AvatarSource.Monogram(
            initials = initials,
            gradientColors = gradientColors,
            drawableIndex = drawableIndex
        )
    }
}
