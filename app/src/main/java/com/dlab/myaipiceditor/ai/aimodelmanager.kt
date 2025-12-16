package com.dlab.myaipiceditor.ai

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Manages the loading and access of both ONNX and TFLite models.
 * Implements lazy loading, caching from assets, thread-safe initialization,
 * and execution provider fallbacks for better performance and reliability.
 */
object AiModelManager {
    private const val TAG = "AiModelManager"
    private const val MODELS_CACHE_DIR = "onnx_models"
    private const val ASSETS_MODELS_FOLDER = "models"

    private lateinit var ortEnvironment: OrtEnvironment
    private val onnxSessions = mutableMapOf<ModelType, OrtSession>()
    private val tfliteInterpreters = mutableMapOf<ModelType, Interpreter>()
    private val loadingMutex = Mutex()
    private var isInitialized = false
    private lateinit var appContext: Context
    private lateinit var cacheDir: File

    enum class ModelType(val fileName: String, val isOnnx: Boolean) {
        OBJECT_REMOVAL("lama_dilated_fp32.onnx", true), // This is our ONNX model
        IMAGE_UPSCALER("Real_ESRGAN_x4plus_float.tflite", false)
    }

    /**
     * Initializes the model manager environment and preloads essential models.
     * This function is thread-safe and idempotent.
     */
    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        if (isInitialized) {
            Log.d(TAG, "AiModelManager already initialized")
            return@withContext
        }

        try {
            Log.d(TAG, "Initializing AiModelManager...")
            appContext = context.applicationContext

            // Initialize ONNX Runtime environment once
            ortEnvironment = OrtEnvironment.getEnvironment()

            cacheDir = File(appContext.filesDir, MODELS_CACHE_DIR)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            // Preload models for quick initial access
            Log.d(TAG, "Starting model preloading...")
            loadModelSequentially(ModelType.IMAGE_UPSCALER)

            isInitialized = true
            Log.d(TAG, "AiModelManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL ERROR initializing AiModelManager: ${e.message}", e)
            throw e
        }
    }

    /**
     * Loads a specific model, ensuring it's done sequentially and safely.
     */
    private suspend fun loadModelSequentially(modelType: ModelType) = withContext(Dispatchers.IO) {
        loadingMutex.withLock {
            // Check if already loaded
            if (modelType.isOnnx && onnxSessions.containsKey(modelType)) {
                Log.d(TAG, "ONNX Model ${modelType.fileName} already loaded")
                return@withLock
            }
            if (!modelType.isOnnx && tfliteInterpreters.containsKey(modelType)) {
                Log.d(TAG, "TFLite Model ${modelType.fileName} already loaded")
                return@withLock
            }

            try {
                Log.d(TAG, "Loading model: ${modelType.fileName} (${if (modelType.isOnnx) "ONNX" else "TFLite"})")

                val cachedModelFile = File(cacheDir, modelType.fileName)

                // Copy model from assets to cache if not exists
                if (!cachedModelFile.exists()) {
                    Log.d(TAG, "Copying ${modelType.fileName} to cache...")
                    copyModelFromAssets(appContext, modelType.fileName, cachedModelFile)
                } else {
                    Log.d(TAG, "${modelType.fileName} already cached")
                }

                // Load based on model type
                if (modelType.isOnnx) {
                    val session = createOnnxSession(modelType, cachedModelFile)
                    if (session != null) {
                        onnxSessions[modelType] = session
                        Log.d(TAG, "ONNX Model ${modelType.fileName} loaded successfully")
                    } else {
                        throw IllegalStateException("Unable to load ONNX model ${modelType.fileName}")
                    }
                } else {
                    val interpreter = createTfliteInterpreter(modelType, cachedModelFile)
                    if (interpreter != null) {
                        tfliteInterpreters[modelType] = interpreter
                        Log.d(TAG, "TFLite Model ${modelType.fileName} loaded successfully")
                    } else {
                        throw IllegalStateException("Unable to load TFLite model ${modelType.fileName}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model ${modelType.fileName}: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Creates an ONNX Runtime session with fallback to CPU if NNAPI fails.
     */
    private fun createOnnxSession(modelType: ModelType, modelFile: File): OrtSession? {
        return try {
            Log.d(TAG, "Creating ONNX session for ${modelType.fileName}")

            val sessionOptions = OrtSession.SessionOptions().apply {
                when (modelType) {
                    ModelType.IMAGE_UPSCALER -> {
                        // EDSR: Moderate optimization
                        setIntraOpNumThreads(4)
                        setInterOpNumThreads(2)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    // --- FIX: Add specific case for OBJECT_REMOVAL ---
                    ModelType.OBJECT_REMOVAL -> {
                        // LAMA: Optimized CPU execution for stability and performance.
                        // 4 threads for intra-op parallelism (speed)
                        setIntraOpNumThreads(4)
                        // 1 thread for inter-op parallelism (stability/sequential execution)
                        setInterOpNumThreads(1)

                        // Critical: Force sequential execution to avoid graph scheduling overhead
                        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)

                        // Use maximum available optimization
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    else -> {
                        // Default settings
                        setIntraOpNumThreads(2)
                        setInterOpNumThreads(1)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    }
                }

            }

            ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ONNX session for ${modelType.fileName}: ${e.message}", e)
            null
        }
    }

    /**
     * Creates a TFLite interpreter with GPU delegate fallback to CPU.
     */
    private fun createTfliteInterpreter(modelType: ModelType, modelFile: File): Interpreter? {
        return try {
            Log.d(TAG, "Creating TFLite interpreter for ${modelType.fileName}")

            val modelBuffer = loadModelFile(modelFile)
            val options = Interpreter.Options().apply {
                // Set number of threads
                setNumThreads(4)

                // ⬇️ NOTE: This check is likely a bug from your original code ⬇️
                // ModelType.OBJECT_REMOVAL is ONNX, so it will never be true here.
                // This doesn't cause harm, but it's incorrect logic.
                // The *real* fix is in createOnnxSession() above.
                if (modelType == ModelType.OBJECT_REMOVAL) {
                    Log.d(TAG, "Forcing CPU for ${modelType.fileName} due to GPU delegate incompatibility.")
                    // No delegate is added, CPU will be used as default.
                } else {
                    // Original logic for other TFLite models
                    // Try GPU delegate if compatible
                    val compatList = CompatibilityList()
                    if (compatList.isDelegateSupportedOnThisDevice) {
                        try {
                            val delegateOptions = compatList.bestOptionsForThisDevice
                            val gpuDelegate = GpuDelegate(delegateOptions)
                            addDelegate(gpuDelegate)
                            Log.d(TAG, "GPU delegate enabled for ${modelType.fileName}")
                        } catch (e: Exception) {
                            Log.w(TAG, "GPU delegate failed for ${modelType.fileName}, using CPU: ${e.message}")
                        }
                    } else {
                        Log.d(TAG, "GPU delegate not supported for ${modelType.fileName}, using CPU")
                    }
                }
            }

            Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create TFLite interpreter for ${modelType.fileName}: ${e.message}", e)
            null
        }
    }

    /**
     * Loads a TFLite model file into a MappedByteBuffer.
     */
    private fun loadModelFile(modelFile: File): MappedByteBuffer {
        FileInputStream(modelFile).use { inputStream ->
            val fileChannel = inputStream.channel
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
        }
    }

    /**
     * Utility function to copy model from assets to local cache.
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
     * Gets the OrtSession for a given ONNX model type.
     */
    suspend fun getSession(modelType: ModelType): OrtSession {
        if (!isInitialized) {
            throw IllegalStateException("AiModelManager not initialized. Call initialize() first.")
        }

        if (!modelType.isOnnx) {
            throw IllegalArgumentException("${modelType.fileName} is not an ONNX model. Use getInterpreter() instead.")
        }

        if (!onnxSessions.containsKey(modelType)) {
            Log.d(TAG, "Lazy loading ${modelType.fileName}...")
            loadModelSequentially(modelType)
        }

        return onnxSessions[modelType]
            ?: throw IllegalStateException("Model ${modelType.fileName} could not be loaded")
    }

    /**
     * Gets the Interpreter for a given TFLite model type.
     */
    suspend fun getInterpreter(modelType: ModelType): Interpreter {
        if (!isInitialized) {
            throw IllegalStateException("AiModelManager not initialized. Call initialize() first.")
        }

        if (modelType.isOnnx) {
            throw IllegalArgumentException("${modelType.fileName} is not a TFLite model. Use getSession() instead.")
        }

        if (!tfliteInterpreters.containsKey(modelType)) {
            Log.d(TAG, "Lazy loading ${modelType.fileName}...")
            loadModelSequentially(modelType)
        }

        return tfliteInterpreters[modelType]
            ?: throw IllegalStateException("Model ${modelType.fileName} could not be loaded")
    }

    /**
     * Gets the single OrtEnvironment instance.
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
        return if (modelType.isOnnx) {
            onnxSessions.containsKey(modelType)
        } else {
            tfliteInterpreters.containsKey(modelType)
        }
    }

    /**
     * Cleans up all loaded sessions and resources.
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up AiModelManager...")

        // Close all ONNX sessions
        onnxSessions.values.forEach { session ->
            try {
                session.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing ONNX session: ${e.message}", e)
            }
        }
        onnxSessions.clear()

        // Close all TFLite interpreters
        tfliteInterpreters.values.forEach { interpreter ->
            try {
                interpreter.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing TFLite interpreter: ${e.message}", e)
            }
        }
        tfliteInterpreters.clear()

        isInitialized = false
        Log.d(TAG, "AiModelManager cleaned up.")
    }
}