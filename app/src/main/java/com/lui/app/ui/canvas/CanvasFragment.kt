package com.lui.app.ui.canvas

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.lui.app.LuiViewModel
import com.lui.app.R
import com.lui.app.databinding.FragmentCanvasBinding

class CanvasFragment : Fragment() {

    private var _binding: FragmentCanvasBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: LuiViewModel
    private lateinit var adapter: MessageAdapter

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

        binding.sendButton.setOnClickListener { sendMessage() }
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
    }

    private fun sendMessage() {
        val text = binding.inputField.text.toString()
        if (text.isBlank()) return

        binding.inputField.text?.clear()
        viewModel.handleUserInput(text)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
