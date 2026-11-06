package com.nugggets.gorodwatcher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.nugggets.gorodwatcher.databinding.FragmentFilterBinding

class FilterFragment : Fragment() {
    private lateinit var binding: FragmentFilterBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentFilterBinding.inflate(inflater, container, false)
        return binding.root
    }
}
