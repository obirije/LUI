package com.lui.app.ui.canvas

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lui.app.R
import com.lui.app.data.ChatMessage
import com.lui.app.data.ChatMessage.Sender

class MessageAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_LUI = 1
        private const val TYPE_WELCOME = 2
        private const val TYPE_THINKING = 3
    }

    private var lastAnimatedPosition = -1
    private var welcomeAnimated = false

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).sender) {
            Sender.USER -> TYPE_USER
            Sender.LUI -> TYPE_LUI
            Sender.WELCOME -> TYPE_WELCOME
            Sender.THINKING -> TYPE_THINKING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_WELCOME -> WelcomeViewHolder(inflater.inflate(R.layout.item_message_welcome, parent, false))
            TYPE_USER -> UserViewHolder(inflater.inflate(R.layout.item_message_user, parent, false))
            TYPE_THINKING -> ThinkingViewHolder(inflater.inflate(R.layout.item_message_thinking, parent, false))
            else -> LuiViewHolder(inflater.inflate(R.layout.item_message_lui, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is WelcomeViewHolder -> holder.bind(message)
            is UserViewHolder -> holder.bind(message)
            is LuiViewHolder -> holder.bind(message)
            is ThinkingViewHolder -> holder.startPulse()
        }

        if (message.sender == Sender.USER && position > lastAnimatedPosition) {
            val animation = AnimationUtils.loadAnimation(holder.itemView.context, R.anim.message_fade_in_user)
            holder.itemView.startAnimation(animation)
            lastAnimatedPosition = position
        }

        if (message.sender == Sender.LUI && position > lastAnimatedPosition && !message.streaming) {
            val animation = AnimationUtils.loadAnimation(holder.itemView.context, R.anim.message_fade_in_lui)
            holder.itemView.startAnimation(animation)
            lastAnimatedPosition = position
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is WelcomeViewHolder && !welcomeAnimated) {
            welcomeAnimated = true
            holder.animateIn()
        }
        if (holder is ThinkingViewHolder) holder.startPulse()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        holder.itemView.clearAnimation()
        if (holder is LuiViewHolder) holder.stopCursor()
        if (holder is ThinkingViewHolder) holder.stopPulse()
        super.onViewDetachedFromWindow(holder)
    }

    // ---- ViewHolders ----

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.messageText)
        fun bind(message: ChatMessage) { textView.text = message.text }
    }

    class ThinkingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val dot1: View = view.findViewById(R.id.dot1)
        private val dot2: View = view.findViewById(R.id.dot2)
        private val dot3: View = view.findViewById(R.id.dot3)
        private var animatorSet: AnimatorSet? = null

        fun startPulse() {
            if (animatorSet != null) return

            val dots = listOf(dot1, dot2, dot3)
            val animators = mutableListOf<android.animation.Animator>()

            dots.forEachIndexed { i, dot ->
                // Alpha: fade in and out
                val alpha = ObjectAnimator.ofFloat(dot, "alpha", 0.2f, 1f, 0.2f).apply {
                    duration = 1200
                    repeatCount = ValueAnimator.INFINITE
                    startDelay = (i * 200).toLong()
                    interpolator = DecelerateInterpolator(1.5f)
                }
                // Scale: subtle bounce
                val scaleX = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 1.4f, 1f).apply {
                    duration = 1200
                    repeatCount = ValueAnimator.INFINITE
                    startDelay = (i * 200).toLong()
                    interpolator = DecelerateInterpolator(1.5f)
                }
                val scaleY = ObjectAnimator.ofFloat(dot, "scaleY", 1f, 1.4f, 1f).apply {
                    duration = 1200
                    repeatCount = ValueAnimator.INFINITE
                    startDelay = (i * 200).toLong()
                    interpolator = DecelerateInterpolator(1.5f)
                }
                animators.addAll(listOf(alpha, scaleX, scaleY))
            }

            animatorSet = AnimatorSet().apply {
                playTogether(animators)
                start()
            }
        }

        fun stopPulse() {
            animatorSet?.cancel()
            animatorSet = null
            listOf(dot1, dot2, dot3).forEach {
                it.alpha = 0.3f
                it.scaleX = 1f
                it.scaleY = 1f
            }
        }
    }

    class LuiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.messageText)
        private val cursorView: TextView = view.findViewById(R.id.cursorView)
        private val imageView: android.widget.ImageView = view.findViewById(R.id.messageImage)
        private var cursorAnimator: ObjectAnimator? = null

        fun bind(message: ChatMessage) {
            // Image preview
            if (message.imageBitmap != null) {
                imageView.setImageBitmap(message.imageBitmap)
                imageView.visibility = View.VISIBLE
            } else {
                imageView.visibility = View.GONE
            }

            if (message.streaming) {
                textView.text = message.text
                textView.alpha = 1f
                cursorView.visibility = View.VISIBLE
                startCursor()
            } else {
                textView.text = message.text
                textView.alpha = 1f
                stopCursor()
                cursorView.visibility = View.GONE
            }
        }

        private fun startCursor() {
            if (cursorAnimator == null) {
                cursorAnimator = ObjectAnimator.ofFloat(cursorView, "alpha", 1f, 0f).apply {
                    duration = 500
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                }
            }
        }

        fun stopCursor() {
            cursorAnimator?.cancel()
            cursorAnimator = null
        }
    }

    class WelcomeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val logoText: TextView = view.findViewById(R.id.logoText)
        private val messageText: TextView = view.findViewById(R.id.messageText)

        fun bind(message: ChatMessage) {
            messageText.text = message.text
            logoText.alpha = 0f
            messageText.alpha = 0f
        }

        fun animateIn() {
            logoText.alpha = 0f
            logoText.scaleX = 0.7f
            logoText.scaleY = 0.7f
            logoText.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(700).setInterpolator(DecelerateInterpolator(2.5f)).start()

            messageText.alpha = 0f
            messageText.translationY = 20f
            messageText.animate().alpha(1f).translationY(0f)
                .setStartDelay(500).setDuration(500).setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
            old.timestamp == new.timestamp && old.sender == new.sender
        override fun areContentsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
            old == new
    }
}
