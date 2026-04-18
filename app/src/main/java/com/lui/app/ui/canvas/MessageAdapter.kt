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
        private const val TYPE_CARD_SEARCH = 4
        private const val TYPE_CARD_STATUS = 5
        private const val TYPE_CARD_HEALTH_TREND = 6
        private const val TYPE_CARD_NOTIFICATIONS = 7
    }

    private var lastAnimatedPosition = -1
    private var welcomeAnimated = false

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        if (msg.cardType != null) {
            return when (msg.cardType) {
                com.lui.app.data.ChatMessage.CardType.SEARCH_RESULTS -> TYPE_CARD_SEARCH
                com.lui.app.data.ChatMessage.CardType.DEVICE_STATUS -> TYPE_CARD_STATUS
                com.lui.app.data.ChatMessage.CardType.LINK_PREVIEW -> TYPE_CARD_SEARCH
                com.lui.app.data.ChatMessage.CardType.HEALTH_TREND_CHART -> TYPE_CARD_HEALTH_TREND
                com.lui.app.data.ChatMessage.CardType.NOTIFICATIONS -> TYPE_CARD_NOTIFICATIONS
            }
        }
        return when (msg.sender) {
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
            TYPE_CARD_SEARCH -> SearchResultsViewHolder(inflater.inflate(R.layout.item_card_search_results, parent, false))
            TYPE_CARD_STATUS -> DeviceStatusViewHolder(inflater.inflate(R.layout.item_card_device_status, parent, false))
            TYPE_CARD_HEALTH_TREND -> HealthTrendViewHolder(inflater.inflate(R.layout.item_card_health_trend, parent, false))
            TYPE_CARD_NOTIFICATIONS -> NotificationsViewHolder(inflater.inflate(R.layout.item_card_notifications, parent, false))
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
            is SearchResultsViewHolder -> holder.bind(message)
            is DeviceStatusViewHolder -> holder.bind(message)
            is HealthTrendViewHolder -> holder.bind(message)
            is NotificationsViewHolder -> holder.bind(message)
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

    class SearchResultsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val container: android.widget.LinearLayout = view.findViewById(R.id.resultsContainer)

        fun bind(message: ChatMessage) {
            container.removeAllViews()
            val inflater = LayoutInflater.from(container.context)
            val results = message.cardData ?: return

            for (result in results) {
                val row = inflater.inflate(R.layout.item_search_result_row, container, false)
                row.findViewById<TextView>(R.id.resultTitle).text = result["title"] ?: ""
                row.findViewById<TextView>(R.id.resultSnippet).text = result["snippet"] ?: ""
                row.findViewById<TextView>(R.id.resultUrl).text = result["url"] ?: ""

                val url = result["url"]
                if (!url.isNullOrBlank()) {
                    row.setOnClickListener {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            container.context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                }

                // Divider between results
                if (container.childCount > 0) {
                    val divider = View(container.context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).apply { topMargin = 2; bottomMargin = 2 }
                        setBackgroundColor(android.graphics.Color.parseColor("#2A2A2A"))
                    }
                    container.addView(divider)
                }
                container.addView(row)
            }
        }
    }

    class DeviceStatusViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val container: android.widget.LinearLayout = view.findViewById(R.id.statusContainer)

        fun bind(message: ChatMessage) {
            container.removeAllViews()
            val inflater = LayoutInflater.from(container.context)
            val rows = message.cardData ?: return

            for (row in rows) {
                val label = row["label"] ?: continue
                val value = row["value"] ?: continue
                val rowView = inflater.inflate(R.layout.item_status_row, container, false)
                rowView.findViewById<TextView>(R.id.statusLabel).text = label
                val valueView = rowView.findViewById<TextView>(R.id.statusValue)
                valueView.text = value

                // Color code certain values
                val color = row["color"]
                if (color != null) {
                    try { valueView.setTextColor(android.graphics.Color.parseColor(color)) } catch (_: Exception) {}
                }

                container.addView(rowView)
            }
        }
    }

    class HealthTrendViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val labelView: TextView = view.findViewById(R.id.trendLabel)
        private val windowView: TextView = view.findViewById(R.id.trendWindow)
        private val sparkline: SparklineView = view.findViewById(R.id.sparkline)
        private val latestView: TextView = view.findViewById(R.id.trendLatest)
        private val avgView: TextView = view.findViewById(R.id.trendAvg)
        private val rangeView: TextView = view.findViewById(R.id.trendRange)

        fun bind(message: ChatMessage) {
            val rows = message.cardData ?: return
            if (rows.isEmpty()) return
            val meta = rows[0]
            val label = meta["label"] ?: "Health"
            val hours = meta["hours"]?.toIntOrNull() ?: 24
            labelView.text = label
            windowView.text = if (hours >= 24 && hours % 24 == 0) "last ${hours / 24}d" else "last ${hours}h"
            latestView.text = meta["latest"]?.substringBefore(" (") ?: "—"
            avgView.text = meta["avg"] ?: "—"
            rangeView.text = meta["range"] ?: "—"

            val points = rows.drop(1).mapNotNull { it["v"]?.toFloatOrNull() }.toFloatArray()
            sparkline.setData(points, colorFor(label))
        }

        private fun colorFor(label: String): Int = when {
            label.contains("Heart", true) -> android.graphics.Color.parseColor("#F44336")
            label.contains("SpO2", true) || label.contains("Oxygen", true) -> android.graphics.Color.parseColor("#42A5F5")
            label.contains("Stress", true) -> android.graphics.Color.parseColor("#FF9800")
            label.contains("HRV", true) -> android.graphics.Color.parseColor("#9C27B0")
            label.contains("Temperature", true) -> android.graphics.Color.parseColor("#FF7043")
            label.contains("Step", true) || label.contains("Activity", true) -> android.graphics.Color.parseColor("#4CAF50")
            label.contains("Sleep", true) -> android.graphics.Color.parseColor("#7E57C2")
            else -> android.graphics.Color.parseColor("#4CAF50")
        }
    }

    class NotificationsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleView: TextView = view.findViewById(R.id.notifTitle)
        private val container: android.widget.LinearLayout = view.findViewById(R.id.notifContainer)

        fun bind(message: ChatMessage) {
            container.removeAllViews()
            val rows = message.cardData ?: return
            if (rows.isEmpty()) return

            // First row is the header (e.g. "Notifications (12 in last 24h):")
            titleView.text = rows[0]["header"] ?: "Notifications"

            val inflater = LayoutInflater.from(container.context)
            var lastApp: String? = null
            for (row in rows.drop(1)) {
                val app = row["app"] ?: continue
                val rowView = inflater.inflate(R.layout.item_notification_row, container, false)
                val appView = rowView.findViewById<TextView>(R.id.notifApp)
                val countView = rowView.findViewById<TextView>(R.id.notifCount)
                val timeView = rowView.findViewById<TextView>(R.id.notifTime)
                val snippetView = rowView.findViewById<TextView>(R.id.notifSnippet)
                val iconView = rowView.findViewById<android.widget.ImageView>(R.id.notifIcon)

                // Only label the first row of each app group; subsequent rows
                // for the same app stay quieter.
                if (app != lastApp) {
                    appView.text = app
                    val count = row["count"]?.toIntOrNull() ?: 0
                    countView.text = if (count > 1) "·$count" else ""
                    iconView.setImageDrawable(loadAppIcon(container.context, app))
                    iconView.alpha = 1f
                } else {
                    appView.text = ""
                    countView.text = ""
                    iconView.setImageDrawable(null)
                }
                lastApp = app

                timeView.text = row["time"] ?: ""
                val title = row["title"] ?: ""
                val snippet = row["snippet"] ?: ""
                snippetView.text = if (snippet.isNotBlank()) "$title — $snippet" else title

                if (container.childCount > 0) {
                    val divider = View(container.context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                        )
                        setBackgroundColor(android.graphics.Color.parseColor("#1F1F1F"))
                    }
                    container.addView(divider)
                }
                container.addView(rowView)
            }
        }

        private val iconCache = mutableMapOf<String, android.graphics.drawable.Drawable?>()
        private fun loadAppIcon(context: android.content.Context, appName: String): android.graphics.drawable.Drawable? {
            iconCache[appName]?.let { return it }
            // Heuristic: try to resolve a package whose label matches the app
            // name. Falls back to null (icon area stays blank).
            val pm = context.packageManager
            val matchedPkg = try {
                pm.getInstalledApplications(0).firstOrNull {
                    pm.getApplicationLabel(it).toString().equals(appName, ignoreCase = true)
                }?.packageName
            } catch (_: Exception) { null }
            val drawable = matchedPkg?.let {
                try { pm.getApplicationIcon(it) } catch (_: Exception) { null }
            }
            iconCache[appName] = drawable
            return drawable
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
            old.timestamp == new.timestamp && old.sender == new.sender
        override fun areContentsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
            old == new
    }
}
