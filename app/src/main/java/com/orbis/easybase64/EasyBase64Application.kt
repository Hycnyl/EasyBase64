package com.orbis.easybase64

import android.app.Application
import com.orbis.easybase64.data.BatchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EasyBase64Application : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository by lazy { BatchRepository(this) }
    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { repository.load() }
    }
}
