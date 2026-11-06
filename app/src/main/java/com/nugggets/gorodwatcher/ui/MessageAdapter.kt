package com.nugggets.gorodwatcher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nugggets.gorodwatcher.data.Message
import com.nugggets.gorodwatcher.databinding.ItemMessageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter : ListAdapter<Message, MessageAdapter.ViewHolder>(DiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(msg: Message) {
            binding.apply {
                title.text = msg.name
                address.text = msg.address
                status.text = msg.statusName
                date.text = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(msg.publishDate))
                theme.text = msg.themeName
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(old: Message, new: Message) = old.id == new.id
        override fun areContentsTheSame(old: Message, new: Message) = old == new
    }
}
