package com.nugggets.gorodwatcher.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.nugggets.gorodwatcher.data.GorodDatabase
import com.nugggets.gorodwatcher.data.Message
import kotlinx.coroutines.launch

class GorodViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = GorodDatabase.getDatabase(app).messageDao()
    val messages: LiveData<List<Message>> = dao.getAllMessages()

    fun insertMessages(messages: List<Message>) {
        viewModelScope.launch {
            dao.insertAll(messages)
        }
    }

    fun clear() {
        viewModelScope.launch {
            dao.deleteAll()
        }
    }
}
