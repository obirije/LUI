package com.lui.app.ui.canvas

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
    }

    private var lastAnimatedPosition = -1
    private var welcomeAnimated = false

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).sender) {
            Sender.USER -> TYPE_USER
            Sender.LUI -> TYPE_LUI
            Sender.WELCOME -> TYPE_WELCOME
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_WELCOME -> WelcomeViewHolder(inflater.inflate(R.layout.item_message_welcome, parent, false))
            TYPE_USER -> UserViewHolder(inflater.inflate(R.layout.item_message_user, parent, false))
            else -> LuiViewHolder(inflater.inflate(R.layout.item_message_lui, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is WelcomeViewHolder -> holder.bind(message)
            is UserViewHolder -> holder.bind(message)
            is LuiViewHolder -> holder.bind(message)
        }

        if (message.sender != Sender.WELCOME && message.sender != Sender.LUI && position > lastAnimatedPosition) {
            val animation = AnimationUtils.loadAnimation(holder.itemView.context, R.anim.message_fade_in_user)
            holder.itemView.startAnimation(animation)
            lastAnimatedPosition = position
        }

        // LUI messages get a fade-in only on first appearance (not during streaming updates)
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
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        holder.itemView.clearAnimation()
        if (holder is LuiViewHolder) holder.stopCursor()
        super.onViewDetachedFromWindow(holder)
    }

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.messageText)

        fun bind(message: ChatMessage) {
            textView.text = message.text
        }
    }

    class LuiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.messageText)
        private val cursorView: TextView = view.findViewById(R.id.cursorView)
        private var cursorAnimator: ObjectAnimator? = null

        fun bind(message: ChatMessage) {
            val isTypingPlaceholder = message.text == "\u2026" && message.streaming

            if (isTypingPlaceholder) {
                // Pulsing dots
                textView.text = "\u2026"
                textView.alpha = 1f
                textView.animate().alpha(0.3f).setDuration(600)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        textView.animate().alpha(1f).setDuration(600).start()
                    }.start()
                cursorView.visibility = View.GONE
            } else if (message.streaming) {
                // Streaming tokens — show text with blinking cursor
                textView.text = message.text
                textView.alpha = 1f
                cursorView.visibility = View.VISIBLE
                startCursor()
            } else {
                // Final state — no cursor
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
            logoText.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(700)
                .setInterpolator(DecelerateInterpolator(2.5f))
                .start()

            messageText.alpha = 0f
            messageText.translationY = 20f
            messageText.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(500)
                .setDuration(500)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
            old.timestamp == new.timestamp && old.sender == new.sender

        override fun areContentsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
            old == new
    }
}
