package com.nugggets.gorodwatcher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.nugggets.gorodwatcher.databinding.FragmentListBinding
import com.nugggets.gorodwatcher.viewmodel.GorodViewModel

class ListFragment : Fragment() {
    private val viewModel: GorodViewModel by viewModels()
    private lateinit var binding: FragmentListBinding
    private val adapter by lazy { MessageAdapter() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        viewModel.messages.observe(viewLifecycleOwner) { adapter.submitList(it) }
    }
}
