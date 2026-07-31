package com.example

import android.app.Application
import com.example.data.repository.DictationRepository

class VozLocalApp : Application() {
    lateinit var repository: DictationRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = DictationRepository(this)
    }
}
