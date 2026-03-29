package com.lui.app.ui.canvas

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.lui.app.LuiViewModel
import com.lui.app.R
import com.lui.app.databinding.FragmentCanvasBinding
import com.lui.app.voice.VoiceEngine
import kotlinx.coroutines.launch

class CanvasFragment : Fragment() {

    private var _binding: FragmentCanvasBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: LuiViewModel
    private lateinit var adapter: MessageAdapter
    private var pulseAnimator: AnimatorSet? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoiceInput()
    }

    private val actionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onPermissionResult(granted)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCanvasBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[LuiViewModel::class.java]

        setupRecyclerView()
        setupInput()
        setupStatusDot()
        setupGestures()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter()
        binding.messageList.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = this@CanvasFragment.adapter
        }
    }

    private fun setupInput() {
        binding.inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        binding.micButton.setOnClickListener {
            val voiceState = viewModel.voiceEngine.state.value
            val inConvMode = viewModel.voiceEngine.conversationMode

            // If in conversation mode: tap to exit
            if (inConvMode && (voiceState == VoiceEngine.State.LISTENING
                        || voiceState == VoiceEngine.State.SPEAKING)) {
                viewModel.voiceEngine.conversationMode = false
                viewModel.voiceEngine.stopSpeaking()
                viewModel.voiceEngine.stopListening()
                return@setOnClickListener
            }

            // If speaking (non-conv), interrupt
            if (voiceState == VoiceEngine.State.SPEAKING) {
                viewModel.voiceEngine.stopSpeaking()
                return@setOnClickListener
            }

            // If listening, stop
            if (voiceState == VoiceEngine.State.LISTENING) {
                viewModel.voiceEngine.stopListening()
                return@setOnClickListener
            }

            // Idle: single voice query
            requestMicAndListen(conversationMode = false)
        }

        // Long-press = conversation mode
        binding.micButton.setOnLongClickListener {
            requestMicAndListen(conversationMode = true)
            true
        }
    }

    private fun requestMicAndListen(conversationMode: Boolean) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startVoiceInput(conversationMode)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupStatusDot() {
        binding.statusDotTapTarget.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_canvas_to_hub)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // S-gesture tracking
    private val gesturePoints = mutableListOf<Pair<Float, Float>>()

    private fun setupGestures() {
        binding.canvasRoot.setOnLongClickListener {
            try {
                findNavController().navigate(R.id.action_canvas_to_drawer)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            true
        }

        // Track finger movement on the canvas for S-gesture
        binding.messageList.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    gesturePoints.clear()
                    gesturePoints.add(Pair(event.x, event.y))
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    gesturePoints.add(Pair(event.x, event.y))
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (isSGesture(gesturePoints)) {
                        try {
                            findNavController().navigate(R.id.action_canvas_to_hub)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    gesturePoints.clear()
                }
            }
            false // Don't consume — let RecyclerView scroll normally
        }
    }

    /**
     * Detect an S-shape: stroke goes right then left (or left then right)
     * with enough vertical movement and at least one horizontal direction change.
     */
    private fun isSGesture(points: List<Pair<Float, Float>>): Boolean {
        if (points.size < 20) return false

        val startY = points.first().second
        val endY = points.last().second
        val verticalDistance = endY - startY

        // Must move down at least 200px
        if (verticalDistance < 200) return false

        // Split into top half and bottom half
        val midIdx = points.size / 2
        val topHalf = points.subList(0, midIdx)
        val bottomHalf = points.subList(midIdx, points.size)

        // Calculate average horizontal direction for each half
        val topDeltaX = topHalf.last().first - topHalf.first().first
        val bottomDeltaX = bottomHalf.last().first - bottomHalf.first().first

        // S-shape: top and bottom halves move in opposite horizontal directions
        // and each half has meaningful horizontal movement (>40px)
        val minHorizontal = 40f
        return (topDeltaX > minHorizontal && bottomDeltaX < -minHorizontal) ||
               (topDeltaX < -minHorizontal && bottomDeltaX > minHorizontal)
    }

    private fun observeViewModel() {
        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            adapter.submitList(messages) {
                if (messages.isNotEmpty()) {
                    binding.messageList.scrollToPosition(messages.size - 1)
                }
            }
        }

        viewModel.llmStatus.observe(viewLifecycleOwner) { status ->
            binding.statusDot.visibility = View.VISIBLE
            when (status) {
                "ready" -> {
                    binding.statusDot.backgroundTintList =
                        ContextCompat.getColorStateList(requireContext(), R.color.lui_blue)
                    binding.inputField.hint = getString(R.string.input_hint)
                }
                "cloud" -> {
                    binding.statusDot.backgroundTintList =
                        ContextCompat.getColorStateList(requireContext(), R.color.lui_green)
                    binding.inputField.hint = getString(R.string.input_hint)
                }
                "no_model" -> {
                    binding.statusDot.backgroundTintList =
                        ContextCompat.getColorStateList(requireContext(), R.color.lui_gray_dark)
                    binding.inputField.hint = "Keyword mode — tap dot to configure"
                }
                else -> {
                    binding.statusDot.backgroundTintList =
                        ContextCompat.getColorStateList(requireContext(), R.color.lui_amber)
                    binding.inputField.hint = status
                }
            }
        }

        // Just-in-time permission requests
        viewModel.permissionRequest.observe(viewLifecycleOwner) { req ->
            if (req != null) {
                actionPermissionLauncher.launch(req.permission)
            }
        }

        // Voice state → animations
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.voiceEngine.state.collect { state ->
                when (state) {
                    VoiceEngine.State.LISTENING -> showListeningState()
                    VoiceEngine.State.SPEAKING -> showSpeakingState()
                    VoiceEngine.State.PROCESSING -> showProcessingState()
                    VoiceEngine.State.IDLE -> showIdleState()
                }
            }
        }
    }

    private fun showListeningState() {
        binding.micButton.setColorFilter(ContextCompat.getColor(requireContext(), R.color.lui_green))
        binding.micPulseRing.visibility = View.VISIBLE
        startPulseAnimation()

        val modeText = if (viewModel.voiceEngine.conversationMode) "conv  tap to exit" else "listening"
        binding.voiceStatusText.text = modeText
        binding.voiceStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_green))
        binding.voiceStatusText.visibility = View.VISIBLE
    }

    private fun showSpeakingState() {
        stopPulseAnimation()
        binding.micButton.setColorFilter(ContextCompat.getColor(requireContext(), R.color.lui_amber))
        binding.micPulseRing.visibility = View.GONE

        binding.voiceStatusText.text = if (viewModel.voiceEngine.conversationMode) "tap to exit" else ""
        binding.voiceStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_amber))
        binding.voiceStatusText.visibility = if (viewModel.voiceEngine.conversationMode) View.VISIBLE else View.GONE
    }

    private fun showProcessingState() {
        stopPulseAnimation()
        binding.micButton.setColorFilter(ContextCompat.getColor(requireContext(), R.color.lui_gray))
        binding.micPulseRing.visibility = View.GONE
        binding.voiceStatusText.visibility = View.GONE
    }

    private fun showIdleState() {
        stopPulseAnimation()
        binding.micButton.setColorFilter(ContextCompat.getColor(requireContext(), R.color.lui_gray))
        binding.micPulseRing.visibility = View.GONE
        binding.voiceStatusText.visibility = View.GONE
    }

    private fun startPulseAnimation() {
        stopPulseAnimation()
        val ring = binding.micPulseRing

        val scaleX = ObjectAnimator.ofFloat(ring, "scaleX", 0.8f, 1.4f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val scaleY = ObjectAnimator.ofFloat(ring, "scaleY", 0.8f, 1.4f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val alpha = ObjectAnimator.ofFloat(ring, "alpha", 0.8f, 0.2f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        pulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 1000
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private fun sendMessage() {
        val text = binding.inputField.text.toString()
        if (text.isBlank()) return

        binding.inputField.text?.clear()
        viewModel.handleUserInput(text)
    }

    override fun onDestroyView() {
        stopPulseAnimation()
        super.onDestroyView()
        _binding = null
    }
}
