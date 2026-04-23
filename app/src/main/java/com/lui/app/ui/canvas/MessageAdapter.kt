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
import kotlinx.coroutines.launch

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
        private const val TYPE_CARD_SLEEP = 8
        private const val TYPE_CARD_BREATHING = 9
        private const val TYPE_CARD_NOW_PLAYING = 10
        private const val TYPE_CARD_NOW_PLAYING_COMPACT = 11
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
                com.lui.app.data.ChatMessage.CardType.SLEEP -> TYPE_CARD_SLEEP
                com.lui.app.data.ChatMessage.CardType.BREATHING -> TYPE_CARD_BREATHING
                com.lui.app.data.ChatMessage.CardType.NOW_PLAYING -> {
                    // Compact layout for music (PIANO, MEDITATION, ACE-Step
                    // generated tracks); immersive neon equalizer for
                    // sound-effect-type ambients (rain, fire, ocean, …).
                    val meta = msg.cardData?.firstOrNull()
                    val isMusic = meta?.get("kind") == "generated" ||
                        meta?.get("sound") in setOf("PIANO", "MEDITATION")
                    if (isMusic) TYPE_CARD_NOW_PLAYING_COMPACT else TYPE_CARD_NOW_PLAYING
                }
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
            TYPE_CARD_SLEEP -> SleepCardViewHolder(inflater.inflate(R.layout.item_card_sleep, parent, false))
            TYPE_CARD_BREATHING -> BreathingCardViewHolder(inflater.inflate(R.layout.item_card_breathing, parent, false))
            TYPE_CARD_NOW_PLAYING -> NowPlayingCardViewHolder(inflater.inflate(R.layout.item_card_now_playing, parent, false))
            TYPE_CARD_NOW_PLAYING_COMPACT -> NowPlayingCardViewHolder(inflater.inflate(R.layout.item_card_now_playing_compact, parent, false))
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
            is SleepCardViewHolder -> holder.bind(message)
            is BreathingCardViewHolder -> holder.bind(message)
            is NowPlayingCardViewHolder -> holder.bind(message)
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

    class SleepCardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val duration: TextView = view.findViewById(R.id.sleepDuration)
        private val qualityBadge: TextView = view.findViewById(R.id.sleepQualityBadge)
        private val timeline: SleepTimelineView = view.findViewById(R.id.sleepTimeline)
        private val deep: TextView = view.findViewById(R.id.sleepDeep)
        private val light: TextView = view.findViewById(R.id.sleepLight)
        private val rem: TextView = view.findViewById(R.id.sleepRem)
        private val awake: TextView = view.findViewById(R.id.sleepAwake)

        fun bind(message: ChatMessage) {
            val rows = message.cardData ?: return
            if (rows.isEmpty()) return
            val meta = rows[0]

            duration.text = meta["duration"] ?: "—"
            qualityBadge.text = meta["quality"] ?: "—"
            qualityBadge.setTextColor(
                when (meta["qualityLevel"]) {
                    "Excellent" -> android.graphics.Color.parseColor("#4CAF50")
                    "Good" -> android.graphics.Color.parseColor("#8BC34A")
                    "Fair" -> android.graphics.Color.parseColor("#FFC107")
                    else -> android.graphics.Color.parseColor("#F44336")
                }
            )
            deep.text = meta["deep"] ?: "—"
            light.text = meta["light"] ?: "—"
            rem.text = meta["rem"] ?: "—"
            awake.text = meta["awake"] ?: "—"

            val segments = rows.drop(1).mapNotNull {
                val stage = it["stage"]?.toIntOrNull(16) ?: return@mapNotNull null
                val mins = it["mins"]?.toIntOrNull() ?: return@mapNotNull null
                stage to mins
            }
            timeline.setSegments(segments)
        }
    }

    /**
     * Renders the breathing-exercise card. The Lottie is driven
     * frame-by-phase so the on-screen pacer visually *matches* the
     * countdown text:
     *
     *   • inhale  → plays frames 90..150 stretched over the chosen
     *               inhale seconds (setSpeed).
     *   • hold-in → pauses at frame 150 for the hold duration.
     *   • exhale  → plays frames 180..330 stretched over the chosen
     *               exhale seconds.
     *   • hold-out→ pauses at frame 330.
     *
     * Frame ranges were derived from the Lottie's scale keyframes
     * (90,150,180,330 at 30fps). The coroutine is cancelled when the
     * holder rebinds to a different message.
     */
    class BreathingCardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val lottie: com.airbnb.lottie.LottieAnimationView = view.findViewById(R.id.breathingLottie)
        private val pattern: TextView = view.findViewById(R.id.breathingPattern)
        private val phase: TextView = view.findViewById(R.id.breathingPhase)
        private val count: TextView = view.findViewById(R.id.breathingCount)
        private val progress: TextView = view.findViewById(R.id.breathingProgress)
        private var pacerJob: kotlinx.coroutines.Job? = null
        private var lastBoundKey: String? = null

        companion object {
            // Derived from the pacer-breathe Lottie's MAIN circle keyframes
            // (layer 15): scales from 28%→210% over frames 0→150 (inhale),
            // holds 150→180 (peak), shrinks 180→330 back to ~26% (exhale).
            // Frames 330→420 are a static rest tail we don't use.
            private const val FPS = 29.97f
            private const val INHALE_START = 0f
            private const val INHALE_END = 150f
            private const val EXHALE_START = 180f
            private const val EXHALE_END = 330f
        }

        fun bind(message: ChatMessage) {
            val meta = message.cardData?.firstOrNull() ?: return
            val patternKey = meta["pattern"] ?: "478"
            val cycles = meta["cycles"]?.toIntOrNull() ?: 4
            val inhale = meta["in"]?.toIntOrNull() ?: 4
            val holdIn = meta["hold"]?.toIntOrNull() ?: 0
            val exhale = meta["out"]?.toIntOrNull() ?: 4
            val holdOut = meta["hold2"]?.toIntOrNull() ?: 0

            pattern.text = when (patternKey) {
                "478" -> "4-7-8"
                "box" -> "BOX"
                "55" -> "5-5"
                else -> patternKey.uppercase()
            }

            val key = "${message.timestamp}-$patternKey"
            if (lastBoundKey == key && pacerJob?.isActive == true) return
            lastBoundKey = key
            pacerJob?.cancel()

            // Don't replay historical sessions when Room rehydrates the
            // chat — once a message is older than one full pacer run, it's
            // a past event and should render in a completed state.
            val sessionMs = (cycles * (inhale + holdIn + exhale + holdOut) * 1000L) + 2000L
            val age = System.currentTimeMillis() - message.timestamp
            if (age > sessionMs) {
                renderCompletedState(cycles)
                return
            }

            pacerJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                runPacer(cycles, inhale, holdIn, exhale, holdOut)
            }
        }

        private fun renderCompletedState(cycles: Int) {
            phase.text = "Completed"
            count.text = "—"
            progress.text = "Earlier session · $cycles cycles"
            try {
                lottie.cancelAnimation()
                lottie.setMinAndMaxFrame(0, 420)
                lottie.frame = EXHALE_END.toInt()  // at-rest small circle
            } catch (_: Exception) {}
        }

        private suspend fun runPacer(cycles: Int, inhale: Int, holdIn: Int, exhale: Int, holdOut: Int) {
            for (cycleIdx in 1..cycles) {
                progress.text = "Cycle $cycleIdx of $cycles"
                if (inhale > 0)  { startInhale(inhale); phaseCountdown("Inhale", inhale) }
                if (holdIn > 0)  { holdAt(INHALE_END); phaseCountdown("Hold", holdIn) }
                if (exhale > 0)  { startExhale(exhale); phaseCountdown("Exhale", exhale) }
                if (holdOut > 0) { holdAt(EXHALE_END); phaseCountdown("Hold", holdOut) }
            }
            phase.text = "Done"
            count.text = "Nice work."
            try { lottie.pauseAnimation() } catch (_: Exception) {}
        }

        private fun startInhale(seconds: Int) = playSegment(INHALE_START, INHALE_END, seconds)
        private fun startExhale(seconds: Int) = playSegment(EXHALE_START, EXHALE_END, seconds)

        /** Plays a frame range, stretched so it lasts [durationSec] on the
         *  wall clock. Uses [setMinAndMaxFrame] as one call + [progress=0]
         *  so the Lottie library snaps cleanly to the new range instead of
         *  clamping the old frame pointer. */
        private fun playSegment(startFrame: Float, endFrame: Float, durationSec: Int) {
            try {
                lottie.cancelAnimation()
                lottie.setMinAndMaxFrame(startFrame.toInt(), endFrame.toInt())
                lottie.progress = 0f
                val speed = (endFrame - startFrame) / (FPS * durationSec.coerceAtLeast(1))
                lottie.speed = speed.coerceAtLeast(0.01f)
                lottie.playAnimation()
            } catch (_: Exception) {}
        }

        private fun holdAt(frame: Float) {
            try {
                lottie.cancelAnimation()
                // Widen the range so setting `frame` is accepted (the lib
                // clamps to min..max). Then park at the requested frame.
                lottie.setMinAndMaxFrame(0, 420)
                lottie.frame = frame.toInt()
            } catch (_: Exception) {}
        }

        private suspend fun phaseCountdown(label: String, seconds: Int) {
            phase.text = label
            for (s in seconds downTo 1) {
                count.text = s.toString()
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    /**
     * Shows an equalizer-style Lottie while audio from [AmbientSoundPlayer]
     * is active. The card is *message-scoped*: it reflects the sound the
     * tool started, not whatever is playing now. When playback stops (or
     * moves to a different sound/file), the Lottie pauses and the status
     * switches to "Stopped".
     */
    class NowPlayingCardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val lottie: com.airbnb.lottie.LottieAnimationView = view.findViewById(R.id.nowPlayingLottie)
        private val kind: TextView = view.findViewById(R.id.nowPlayingKind)
        private val name: TextView = view.findViewById(R.id.nowPlayingName)
        private val status: TextView = view.findViewById(R.id.nowPlayingStatus)
        private val stopBtn: android.widget.ImageButton = view.findViewById(R.id.nowPlayingStop)
        private var pollJob: kotlinx.coroutines.Job? = null
        private var lastBoundTs: Long = 0L

        fun bind(message: ChatMessage) {
            val meta = message.cardData?.firstOrNull() ?: return
            val kindKey = meta["kind"] ?: "ambient"
            val label = meta["label"]?.takeIf { it.isNotBlank() } ?: "Audio"
            val soundName = meta["sound"]
            val filename = meta["file"]

            kind.text = when (kindKey) {
                "wellness" -> "WELLNESS MODE"
                "generated" -> "GENERATED"
                else -> "AMBIENT"
            }
            name.text = label

            // Reconcile state IMMEDIATELY on every bind — stale cards in
            // chat history must not auto-play. The Lottie's autoPlay is
            // off in XML; we flip it on here only if this card *is* the
            // currently-playing audio.
            renderState(isCurrentlyLive(soundName, filename))

            stopBtn.setOnClickListener {
                com.lui.app.audio.AmbientSoundPlayer.stop(itemView.context)
                renderState(false)
            }

            if (lastBoundTs == message.timestamp && pollJob?.isActive == true) return
            lastBoundTs = message.timestamp

            // 500ms poll keeps the live card in sync when audio stops from
            // elsewhere (voice "stop sound", another card's stop button,
            // or wellness-mode ending).
            pollJob?.cancel()
            pollJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                while (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                    renderState(isCurrentlyLive(soundName, filename))
                    kotlinx.coroutines.delay(500)
                }
            }
        }

        private fun isCurrentlyLive(soundName: String?, filename: String?): Boolean {
            val playing = com.lui.app.audio.AmbientSoundPlayer.currentlyPlaying
            val playingFile = com.lui.app.audio.AmbientSoundPlayer.currentlyPlayingFile
            return when {
                soundName != null -> playing?.name == soundName
                filename != null -> playingFile?.name == filename
                else -> playing != null || playingFile != null
            }
        }

        private fun renderState(live: Boolean) {
            if (live) {
                if (!lottie.isAnimating) try { lottie.playAnimation() } catch (_: Exception) {}
                status.text = "Playing"
                status.setTextColor(android.graphics.Color.parseColor("#9ED8A9"))
                stopBtn.visibility = View.VISIBLE
            } else {
                if (lottie.isAnimating) try { lottie.pauseAnimation() } catch (_: Exception) {}
                try { lottie.progress = 0f } catch (_: Exception) {}
                status.text = "Stopped"
                status.setTextColor(android.graphics.Color.parseColor("#9E9480"))
                stopBtn.visibility = View.GONE
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
            old.timestamp == new.timestamp && old.sender == new.sender
        override fun areContentsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
            old == new
    }
}
