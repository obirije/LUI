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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCanvasBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[LuiViewModel::class.java]

        setupRecyclerView()
        setupInput()
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

    private fun setupGestures() {
        binding.canvasRoot.setOnLongClickListener {
            try {
                findNavController().navigate(R.id.action_canvas_to_drawer)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            true
        }
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
                        ContextCompat.getColorStateList(requireContext(), R.color.lui_green)
                    binding.inputField.hint = getString(R.string.input_hint)
                }
                "no_model" -> {
                    binding.statusDot.backgroundTintList =
                        ContextCompat.getColorStateList(requireContext(), R.color.lui_gray_dark)
                    binding.inputField.hint = "Keyword mode (no model loaded)"
                }
                else -> {
                    binding.statusDot.backgroundTintList =
                        ContextCompat.getColorStateList(requireContext(), R.color.lui_amber)
                    binding.inputField.hint = status
                }
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
