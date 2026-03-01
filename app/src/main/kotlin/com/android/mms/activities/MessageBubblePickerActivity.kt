package com.android.mms.activities

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.common.helper.IconItem
import com.android.common.view.MVSideFrame
import com.android.mms.R
import com.android.mms.adapters.MessageBubblePickerAdapter
import com.android.mms.databinding.ActivityMessageBubblePickerBinding
import com.android.mms.extensions.config
import com.android.mms.helpers.BUBBLE_DRAWABLE_OPTIONS
import com.android.mms.helpers.refreshMessages
import com.goodwy.commons.extensions.getColoredDrawableWithColor
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.updateTextColors
import eightbitlab.com.blurview.BlurTarget

class MessageBubblePickerActivity : SimpleActivity() {
    private lateinit var binding: ActivityMessageBubblePickerBinding
    private var pendingSelectedOptionId = 0
    private var scrollView: View? = null
    private var totalOffset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageBubblePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingSelectedOptionId = config.bubbleDrawableSet
        initTheme()
        initMVSideFrames()
        initBouncy()
        initBouncyListener()
        makeSystemBarsToTransparent()
        setupTopBar()
        setupActionTabs()
        setupList()
    }

    override fun onResume() {
        super.onResume()
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

        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.rootView.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        binding.bubblePickerList.setBackgroundColor(backgroundColor)
        updateTextColors(binding.rootView)
        setupTopBar()
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun initMVSideFrames() {
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
        findViewById<MVSideFrame>(R.id.m_vertical_side_frame_top).bindBlurTarget(blurTarget)
        findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom).bindBlurTarget(blurTarget)
    }

    private fun initBouncy() {
        scrollView = findViewById(R.id.nest_scroll)
        binding.bubblePickerAppbar.post {
            totalOffset = binding.bubblePickerAppbar.totalScrollRange
        }
    }

    private fun initBouncyListener() {
        binding.bubblePickerAppbar.setupOffsetListener { verticalOffset, height ->
            val h = if (height > 0) height else 1
            binding.bubblePickerAppbar.titleView?.scaleX = (1 + 0.7f * verticalOffset / h)
            binding.bubblePickerAppbar.titleView?.scaleY = (1 + 0.7f * verticalOffset / h)
        }
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = if (ime.bottom > 0) ime.bottom else navHeight
            val dp5 = (5 * resources.displayMetrics.density).toInt()
            binding.mVerticalSideFrameBottom.layoutParams =
                binding.mVerticalSideFrameBottom.layoutParams.apply { height = navHeight + dp5 }

            val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
            val actionLayoutParams = binding.lytAction.layoutParams as ViewGroup.MarginLayoutParams
            actionLayoutParams.bottomMargin = bottomInset + activityMargin
            binding.lytAction.layoutParams = actionLayoutParams

            binding.bubblePickerList.setPadding(
                binding.bubblePickerList.paddingLeft,
                binding.bubblePickerList.paddingTop,
                binding.bubblePickerList.paddingRight,
                bottomInset + activityMargin + dp(90)
            )
            insets
        }
    }

    private fun setupTopBar() {
        binding.bubblePickerAppbar.setTitle(getString(com.goodwy.strings.R.string.speech_bubble))
        binding.bubblePickerAppbar.toolbar?.apply {
            val textColor = getProperTextColor()
            navigationIcon = resources.getColoredDrawableWithColor(
                this@MessageBubblePickerActivity,
                com.android.common.R.drawable.ic_cmn_arrow_left_fill,
                textColor
            )
            setNavigationOnClickListener { cancelAndFinish() }
            setNavigationContentDescription(com.goodwy.commons.R.string.back)
        }
        binding.bubblePickerAppbar.titleView?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = (64 * resources.displayMetrics.density).toInt()
        }
    }

    private fun setupActionTabs() {
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
        binding.confirmTab.setTabs(this, items, binding.mainBlurTarget)
        binding.confirmTab.setOnClickedListener { index ->
            when (index) {
                0 -> cancelAndFinish()
                1 -> applySelectionAndFinish()
            }
        }
    }

    private fun cancelAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun applySelectionAndFinish() {
        if (config.bubbleDrawableSet != pendingSelectedOptionId) {
            config.bubbleDrawableSet = pendingSelectedOptionId
            refreshMessages()
        }
        finish()
    }

    private fun setupList() {
        binding.bubblePickerList.layoutManager = LinearLayoutManager(this)
        binding.bubblePickerList.adapter = MessageBubblePickerAdapter(
            items = BUBBLE_DRAWABLE_OPTIONS,
            selectedOptionId = pendingSelectedOptionId
        ) { selected ->
            pendingSelectedOptionId = selected.id
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
