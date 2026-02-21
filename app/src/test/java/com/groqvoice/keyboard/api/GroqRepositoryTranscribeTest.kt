package com.groqvoice.keyboard.api

import com.groqvoice.keyboard.model.TranscriptionResponse
import com.groqvoice.keyboard.model.TranscriptionResult
import com.groqvoice.keyboard.utils.FileCacheManager
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Response
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class GroqRepositoryTranscribeTest {

    @Test
    fun `transcribe returns success and deletes temp file`() = runBlocking {
        val api = FakeGroqApiService(
            transcribeCall = {
                Response.success(TranscriptionResponse(text = "hello world"))
            }
        )
        val file = createTempAudioFile()
        val repository = buildRepository(api)

        val result = repository.transcribe(file, shouldQueueOnNetworkFailure = false)

        assertTrue(result is TranscriptionResult.Success)
        assertEquals("hello world", (result as TranscriptionResult.Success).text)
        assertFalse(file.exists())
    }

    @Test
    fun `transcribe maps 401 and deletes temp file`() = runBlocking {
        val api = FakeGroqApiService(
            transcribeCall = {
                Response.error(
                    401,
                    """{"error":{"message":"invalid_api_key"}}"""
                        .toResponseBody("application/json".toMediaType())
                )
            }
        )
        val file = createTempAudioFile()
        val repository = buildRepository(api)

        val result = repository.transcribe(file, shouldQueueOnNetworkFailure = false)

        assertTrue(result is TranscriptionResult.Failure)
        assertEquals(401, (result as TranscriptionResult.Failure).httpCode)
        assertFalse(file.exists())
    }

    @Test
    fun `transcribe queues when offline and keeps file for worker`() = runBlocking {
        val api = FakeGroqApiService(
            transcribeCall = {
                Response.success(TranscriptionResponse(text = "should not be called"))
            }
        )
        val file = createTempAudioFile()
        val scheduler = FakeRetryScheduler()
        val repository = buildRepository(
            api = api,
            retryScheduler = scheduler,
            networkStatusProvider = object : NetworkStatusProvider {
                override fun isNetworkAvailable(): Boolean = false
            }
        )

        val result = repository.transcribe(file, shouldQueueOnNetworkFailure = true)

        assertTrue(result is TranscriptionResult.Queued)
        assertEquals(1, scheduler.enqueuedCount)
        assertTrue(file.exists())
        file.delete()
    }

    private fun buildRepository(
        api: GroqApiService,
        retryScheduler: TranscriptionRetryScheduler? = null,
        networkStatusProvider: NetworkStatusProvider? = null
    ): GroqRepository {
        val context = RuntimeEnvironment.getApplication()
        return GroqRepository(
            apiKeyProvider = { "test-key" },
            fileCacheManager = FileCacheManager(context),
            retryScheduler = retryScheduler,
            networkStatusProvider = networkStatusProvider,
            apiOverride = api
        )
    }

    private fun createTempAudioFile(): File {
        val context = RuntimeEnvironment.getApplication()
        return FileCacheManager(context).createTempWavFile().apply {
            writeBytes(ByteArray(256) { 1 })
        }
    }

    private class FakeRetryScheduler : TranscriptionRetryScheduler {
        var enqueuedCount: Int = 0

        override fun enqueue(audioFile: File, model: String, language: String?): UUID {
            enqueuedCount++
            return UUID.randomUUID()
        }
    }

    private class FakeGroqApiService(
        private val transcribeCall: suspend () -> Response<TranscriptionResponse>
    ) : GroqApiService {

        override suspend fun transcribeAudio(
            file: MultipartBody.Part,
            model: RequestBody,
            language: RequestBody?,
            temperature: RequestBody?,
            responseFormat: RequestBody?
        ): Response<TranscriptionResponse> = transcribeCall()

        override suspend fun listModels(): Response<Unit> = Response.success(Unit)
    }
}
