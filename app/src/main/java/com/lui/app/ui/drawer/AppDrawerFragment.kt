package com.lui.app.ui.drawer

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lui.app.R
import com.lui.app.databinding.FragmentAppDrawerBinding
import com.lui.app.interceptor.actions.AppLauncher

class AppDrawerFragment : Fragment() {

    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    private var allApps: List<AppLauncher.AppInfo> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        allApps = AppLauncher.getInstalledApps(requireContext())

        val adapter = AppAdapter(allApps) { app ->
            AppLauncher.openApp(requireContext(), app.label)
            findNavController().popBackStack()
        }

        binding.appList.layoutManager = LinearLayoutManager(requireContext())
        binding.appList.adapter = adapter
        binding.appList.itemAnimator?.apply {
            addDuration = 200
            removeDuration = 150
            moveDuration = 200
        }

        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.lowercase() ?: ""
                val filtered = if (query.isBlank()) allApps
                else allApps.filter { it.label.lowercase().contains(query) }
                adapter.updateList(filtered)
            }
        })

        binding.searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else false
        }

        binding.searchField.requestFocus()
        showKeyboard()
    }

    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        binding.searchField.postDelayed({
            imm.showSoftInput(binding.searchField, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchField.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class AppAdapter(
    private var apps: List<AppLauncher.AppInfo>,
    private val onClick: (AppLauncher.AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.appName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.textView.text = app.label
        holder.itemView.setOnClickListener { onClick(app) }
    }

    override fun getItemCount() = apps.size

    fun updateList(newApps: List<AppLauncher.AppInfo>) {
        val diff = DiffUtil.calculateDiff(AppDiffCallback(apps, newApps))
        apps = newApps
        diff.dispatchUpdatesTo(this)
    }

    private class AppDiffCallback(
        private val old: List<AppLauncher.AppInfo>,
        private val new: List<AppLauncher.AppInfo>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos].packageName == new[newPos].packageName
        override fun areContentsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos] == new[newPos]
    }
}
