package com.groqvoice.keyboard.api

import com.groqvoice.keyboard.api.GroqRepository.ApiKeyValidationResult
import com.groqvoice.keyboard.model.TranscriptionResponse
import com.groqvoice.keyboard.utils.FileCacheManager
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class GroqRepositoryApiKeyValidationTest {

    @Test
    fun `validateApiKeyDetailed returns Valid on successful models call`() = runBlocking {
        val repository = buildRepository(
            api = FakeGroqApiService(
                listModelsCall = { Response.success(Unit) }
            )
        )

        val result = repository.validateApiKeyDetailed()

        assertEquals(ApiKeyValidationResult.Valid, result)
    }

    @Test
    fun `validateApiKeyDetailed returns Unauthorized on 401`() = runBlocking {
        val repository = buildRepository(
            api = FakeGroqApiService(
                listModelsCall = {
                    Response.error(
                        401,
                        """{"error":{"message":"invalid_api_key"}}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                }
            )
        )

        val result = repository.validateApiKeyDetailed()

        assertEquals(ApiKeyValidationResult.Unauthorized, result)
    }

    @Test
    fun `validateApiKeyDetailed returns HttpError for non-401 http failure`() = runBlocking {
        val repository = buildRepository(
            api = FakeGroqApiService(
                listModelsCall = {
                    Response.error(
                        503,
                        """{"error":{"message":"service_unavailable"}}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                }
            )
        )

        val result = repository.validateApiKeyDetailed()

        assertEquals(ApiKeyValidationResult.HttpError(503), result)
    }

    @Test
    fun `validateApiKeyDetailed returns NetworkError on IOException`() = runBlocking {
        val repository = buildRepository(
            api = FakeGroqApiService(
                listModelsCall = { throw java.io.IOException("offline") }
            )
        )

        val result = repository.validateApiKeyDetailed()

        assertEquals(ApiKeyValidationResult.NetworkError, result)
    }

    private fun buildRepository(api: GroqApiService): GroqRepository {
        val context = RuntimeEnvironment.getApplication()
        return GroqRepository(
            apiKeyProvider = { "test-key" },
            fileCacheManager = FileCacheManager(context),
            apiOverride = api
        )
    }

    private class FakeGroqApiService(
        private val listModelsCall: suspend () -> Response<Unit>
    ) : GroqApiService {

        override suspend fun transcribeAudio(
            file: MultipartBody.Part,
            model: RequestBody,
            language: RequestBody?,
            temperature: RequestBody?,
            responseFormat: RequestBody?
        ): Response<TranscriptionResponse> {
            throw UnsupportedOperationException("Not needed for this test")
        }

        override suspend fun listModels(): Response<Unit> = listModelsCall()
    }
}
