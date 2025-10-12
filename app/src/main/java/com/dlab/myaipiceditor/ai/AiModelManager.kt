package com.dlab.myaipiceditor.ai

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Manages the loading and access of ONNX models using the ONNX Runtime (ORT).
 * It implements lazy loading, caching from assets, thread-safe initialization,
 * and execution provider fallbacks (NNAPI to CPU) for better performance and reliability.
 *
 * NOTE: Segmentation logic now uses the split MOBILE_SAM_ENCODER and MOBILE_SAM_DECODER models.
 */
object AiModelManager {
    private const val TAG = "AiModelManager"
    private const val MODELS_CACHE_DIR = "onnx_models"
    private const val ASSETS_MODELS_FOLDER = "models"

    private lateinit var ortEnvironment: OrtEnvironment
    private val modelSessions = mutableMapOf<ModelType, OrtSession>()
    private val loadingMutex = Mutex()
    private var isInitialized = false
    private lateinit var appContext: Context
    private lateinit var cacheDir: File

    enum class ModelType(val fileName: String) {
        FACE_RESTORATION("GFPGANv1.4.onnx"),
        OBJECT_REMOVAL("aotgan_float.tflite"),
        IMAGE_UPSCALER("edsr_onnxsim_2x.onnx"),
        FOR_SEGMENTATION("FastSamS_float.tflite")
        
    }

    /**
     * Initializes the ONNX Runtime environment and preloads essential models.
     * This function is thread-safe and idempotent.
     * Must be called once, typically in the application start-up logic.
     */
    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        if (isInitialized) {
            Log.d(TAG, "AiModelManager already initialized")
            return@withContext
        }

        try {
            Log.d(TAG, "Initializing AiModelManager...")
            appContext = context.applicationContext
            // Initialize the environment once
            ortEnvironment = OrtEnvironment.getEnvironment()

            cacheDir = File(appContext.filesDir, MODELS_CACHE_DIR)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            // Preload models for quick initial access
            Log.d(TAG, "Starting model preloading...")
            // Face Restoration is a good candidate for initial load
            loadModelSequentially(ModelType.FACE_RESTORATION)
            // Load both MobileSAM components for segmentation workflow
            loadModelSequentially(ModelType.MOBILE_SAM_ENCODER)
            loadModelSequentially(ModelType.MOBILE_SAM_DECODER)

            isInitialized = true
            Log.d(TAG, "AiModelManager initialized (preloaded: Face Restoration, MobileSam Encoder & Decoder)")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL ERROR initializing AiModelManager: ${e.message}", e)
            // Re-throw the exception to signal initialization failure
            throw e
        }
    }

    /**
     * Loads a specific model, ensuring it's done sequentially and safely.
     * Copies model from assets to local cache if necessary.
     */
    private suspend fun loadModelSequentially(modelType: ModelType) = withContext(Dispatchers.IO) {
        // Use mutex to ensure only one thread attempts to load a model at a time
        loadingMutex.withLock {
            if (modelSessions.containsKey(modelType)) {
                Log.d(TAG, "Model ${modelType.fileName} already loaded")
                return@withLock
            }

            try {
                Log.d(TAG, "Loading model: ${modelType.fileName}")

                val cachedModelFile = File(cacheDir, modelType.fileName)

                // 1. Copy model from assets to cache if not exists
                if (!cachedModelFile.exists()) {
                    Log.d(TAG, "Copying ${modelType.fileName} to cache...")
                    copyModelFromAssets(appContext, modelType.fileName, cachedModelFile)
                } else {
                    Log.d(TAG, "${modelType.fileName} already cached")
                }

                // 2. Create the ONNX Runtime session with fallbacks
                val session = createSessionWithFallback(modelType, cachedModelFile)

                if (session != null) {
                    modelSessions[modelType] = session
                    Log.d(TAG, "Model ${modelType.fileName} loaded successfully")
                } else {
                    Log.e(TAG, "Failed to create session for ${modelType.fileName}")
                    throw IllegalStateException("Unable to load model ${modelType.fileName}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model ${modelType.fileName}: ${e.message}", e)
                // Re-throw to indicate the loading failure
                throw e
            }
        }
    }

    /**
     * Tries to create an optimized session based on the model type, with a conservative CPU fallback.
     */
    private fun createSessionWithFallback(modelType: ModelType, modelFile: File): OrtSession? {
        // Attempt 1: Specific optimized session (NNAPI preference)
        return try {
            when (modelType) {
                ModelType.OBJECT_REMOVAL -> createLamaSession(modelFile)
                // MobileSAM components are now forced to use the CPU session
                ModelType.MOBILE_SAM_ENCODER, ModelType.MOBILE_SAM_DECODER -> createMobileSamSession(modelFile, modelType.fileName)
                else -> createStandardSession(modelType, modelFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Primary session creation failed for ${modelType.fileName}, trying conservative CPU fallback: ${e.message}", e)
            // Attempt 2: Fallback to the most conservative CPU-only settings
            try {
                createConservativeSession(modelFile)
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Fallback conservative session creation also failed for ${modelType.fileName}: ${fallbackException.message}", fallbackException)
                null
            }
        }
    }

    /**
     * Optimized session creation for the LaMa inpainting model.
     * Uses conservative CPU settings due to its memory intensity.
     */
    private fun createLamaSession(modelFile: File): OrtSession {
        Log.d(TAG, "Creating LaMa session with conservative CPU-only settings")

        val sessionOptions = OrtSession.SessionOptions().apply {
            // LaMa is very memory intensive; keep threads low for stability
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        }

        return ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
    }

    /**
     * Specific session creation for the MobileSAM segmentation model components.
     * Note: This session is explicitly configured to use only the CPU Execution Provider
     * for stability and predictability, overriding any NNAPI preference.
     */
    private fun createMobileSamSession(modelFile: File, modelName: String): OrtSession {
        Log.d(TAG, "Creating MobileSAM session for $modelName, forcing CPU execution provider.")

        val sessionOptions = OrtSession.SessionOptions().apply {
            // Balanced thread count for concurrent encoder/decoder use
            setIntraOpNumThreads(3)
            setInterOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)

            // NNAPI is intentionally not added here to force CPU execution.
        }

        return ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
    }

    /**
     * Standard session creation used for models like GFPGAN and Upscaler.
     * Aggressive optimization and attempts to use NNAPI.
     */
    private fun createStandardSession(modelType: ModelType, modelFile: File): OrtSession {
        Log.d(TAG, "Creating standard session for ${modelType.fileName}")

        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) // Use aggressive optimization

            try {
                addNnapi()
                Log.d(TAG, "NNAPI execution provider enabled for ${modelType.fileName}")
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI not available for ${modelType.fileName}, using CPU: ${e.message}")
            }
        }

        return ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
    }

    /**
     * The most conservative session fallback, using minimal threads and no optimization.
     * Ensures functionality even on low-spec or constrained environments.
     */
    private fun createConservativeSession(modelFile: File): OrtSession {
        Log.w(TAG, "Creating conservative fallback session (CPU, NO_OPT)")

        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT) // Disable optimization
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        }

        return ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
    }

    /**
     * Utility function to copy the model file from the app's assets folder to the local cache.
     * @throws IOException if the file copy fails.
     */
    private fun copyModelFromAssets(context: Context, fileName: String, destination: File) {
        try {
            context.assets.open("$ASSETS_MODELS_FOLDER/$fileName").use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error copying model $fileName from assets: ${e.message}", e)
            throw e
        }
    }

    /**
     * Gets the OrtSession for a given model type, initiating lazy loading if not already loaded.
     * @throws IllegalStateException if AiModelManager has not been initialized.
     * @throws IllegalStateException if the model could not be loaded.
     */
    suspend fun getSession(modelType: ModelType): OrtSession {
        if (!isInitialized) {
            throw IllegalStateException("AiModelManager not initialized. Call initialize() first.")
        }

        // Check outside the lock for fast access
        if (!modelSessions.containsKey(modelType)) {
            Log.d(TAG, "Lazy loading ${modelType.fileName} on first use...")
            // Call the loading function which handles the internal lock
            loadModelSequentially(modelType)
        }

        return modelSessions[modelType]
            ?: throw IllegalStateException("Model ${modelType.fileName} could not be loaded after lazy attempt")
    }

    /**
     * Gets the single OrtEnvironment instance.
     * @throws IllegalStateException if AiModelManager has not been initialized.
     */
    fun getEnvironment(): OrtEnvironment {
        if (!isInitialized) {
            throw IllegalStateException("AiModelManager not initialized. Call initialize() first.")
        }
        return ortEnvironment
    }

    /**
     * Checks if a model is currently loaded.
     */
    fun isModelLoaded(modelType: ModelType): Boolean {
        return modelSessions.containsKey(modelType)
    }

    /**
     * Cleans up all loaded sessions and resources.
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up AiModelManager...")
        // Close all sessions
        modelSessions.values.forEach { session ->
            try {
                session.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing session: ${e.message}", e)
            }
        }
        modelSessions.clear()

        // The OrtEnvironment is a singleton and is often shared across multiple components.
        // It's generally safer to let the app/framework handle its destruction unless
        // explicitly required, but we reset the state flags.
        isInitialized = false
        Log.d(TAG, "AiModelManager cleaned up.")
    }
}
