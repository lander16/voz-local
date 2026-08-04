package dev.sebastian.vozlocal

import android.app.Application
import dev.sebastian.vozlocal.data.repository.DictationRepository

class VozLocalApp : Application() {
    lateinit var repository: DictationRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = DictationRepository(this)
    }
}
