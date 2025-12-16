package com.dlab.myaipiceditor

import android.app.Application
import android.util.Log
import com.dlab.myaipiceditor.ai.AiModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

class MyApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Log.d("MyApplication", "Application starting...")
        if (OpenCVLoader.initLocal()) {
            Log.i("OpenCV", "OpenCV loaded successfully");
        } else {
            Log.e("OpenCV", "OpenCV initialization failed!");
        }

        applicationScope.launch {
            try {
                AiModelManager.initialize(applicationContext)
                Log.d("MyApplication", "AI models preloaded successfully")
            } catch (e: Exception) {
                Log.e("MyApplication", "Failed to preload AI models", e)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        AiModelManager.cleanup()
    }
}