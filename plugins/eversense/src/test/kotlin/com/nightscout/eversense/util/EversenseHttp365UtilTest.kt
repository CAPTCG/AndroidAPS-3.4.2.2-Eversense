package com.nightscout.eversense.util

import android.content.SharedPreferences
import com.nightscout.eversense.enums.EversenseTrendArrow
import com.nightscout.eversense.models.EversenseCGMResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class EversenseHttp365UtilTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    private val validTokenJson = """
        {
            "access_token": "test_access_token_abc123",
            "expires_in": 3600,
            "token_type": "Bearer",
            "expires": "2099-01-01T00:00:00Z",
            "lastLogin": "2026-04-10T00:00:00Z"
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val baseUrl = mockWebServer.url("/").toString()
        EversenseHttp365Util.tokenBaseUrl = baseUrl
        EversenseHttp365Util.uploadBaseUrl = baseUrl
        EversenseHttp365Util.careBaseUrl = baseUrl

        prefs = mock()
        editor = mock()
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putString(any(), anyOrNull())).thenReturn(editor)
        whenever(editor.putLong(any(), any())).thenReturn(editor)
        whenever(editor.commit()).thenReturn(true)
        whenever(editor.apply()).then { }

        // Default: no stored state (empty secure state)
        whenever(prefs.getString(StorageKeys.SECURE_STATE, null)).thenReturn(null)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
        // Restore production URLs
        EversenseHttp365Util.tokenBaseUrl = "https://usiamapi.eversensedms.com/"
        EversenseHttp365Util.uploadBaseUrl = "https://usmobileappmsprod.eversensedms.com/"
        EversenseHttp365Util.careBaseUrl = "https://usapialpha.eversensedms.com/"
    }

    // ─── getOrRefreshToken ────────────────────────────────────────────────────

    @Test
    fun `getOrRefreshToken returns cached token when not expired`() {
        val futureExpiry = System.currentTimeMillis() + 600_000L // 10 minutes from now
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(futureExpiry)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("cached_token_xyz")

        val token = EversenseHttp365Util.getOrRefreshToken(prefs)

        assertEquals("cached_token_xyz", token)
        // No requests should have been made to the server
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun `getOrRefreshToken fetches new token when cache is expired`() {
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(0L)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn(null)
        whenever(prefs.getString(StorageKeys.SECURE_STATE, null)).thenReturn(
            """{"username":"user@example.com","password":"testpass"}"""
        )

        mockWebServer.enqueue(MockResponse().setBody(validTokenJson).setResponseCode(200))

        val token = EversenseHttp365Util.getOrRefreshToken(prefs)

        assertEquals("test_access_token_abc123", token)
        assertEquals(1, mockWebServer.requestCount)
        val request = mockWebServer.takeRequest()
        assertEquals("/connect/token", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.body.readUtf8().contains("grant_type=password"))
    }

    @Test
    fun `getOrRefreshToken returns null when login fails`() {
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(0L)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn(null)

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_client"}"""))

        val token = EversenseHttp365Util.getOrRefreshToken(prefs)

        assertNull(token)
    }

    @Test
    fun `getOrRefreshToken refreshes token within 5 minutes of expiry`() {
        // Token expires in 4 minutes — within the 5-minute refresh window
        val nearExpiryMs = System.currentTimeMillis() + 240_000L
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(nearExpiryMs)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("old_token")
        whenever(prefs.getString(StorageKeys.SECURE_STATE, null)).thenReturn(
            """{"username":"user@example.com","password":"testpass"}"""
        )

        mockWebServer.enqueue(MockResponse().setBody(validTokenJson).setResponseCode(200))

        val token = EversenseHttp365Util.getOrRefreshToken(prefs)

        assertEquals("test_access_token_abc123", token)
        assertEquals(1, mockWebServer.requestCount)
    }

    // ─── uploadGlucoseReadings ────────────────────────────────────────────────

    @Test
    fun `uploadGlucoseReadings posts to correct endpoint with bearer token`() {
        val futureExpiry = System.currentTimeMillis() + 600_000L
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(futureExpiry)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("my_bearer_token")

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val readings = listOf(
            EversenseCGMResult(
                glucoseInMgDl = 120,
                datetime = 1700000000000L,
                trend = EversenseTrendArrow.FLAT,
                sensorId = "sensor_001",
                rawResponseHex = "deadbeef"
            )
        )

        val result = EversenseHttp365Util.uploadGlucoseReadings(prefs, readings, "TX-12345", "1.2.3")

        assertTrue(result, "Expected upload to return true on HTTP 200")
        assertEquals(1, mockWebServer.requestCount)
        val request = mockWebServer.takeRequest()
        assertEquals("/api/v1.0/DiagnosticLog/PostEssentialLogs", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer my_bearer_token", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Content-Type"))
    }

    @Test
    fun `uploadGlucoseReadings sends correct JSON body fields`() {
        val futureExpiry = System.currentTimeMillis() + 600_000L
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(futureExpiry)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("my_bearer_token")

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val readings = listOf(
            EversenseCGMResult(
                glucoseInMgDl = 95,
                datetime = 1700000000000L,
                trend = EversenseTrendArrow.FLAT,
                sensorId = "abc123",
                rawResponseHex = "cafebabe"
            )
        )

        EversenseHttp365Util.uploadGlucoseReadings(prefs, readings, "TXSERIAL", "2.0.1")

        val body = mockWebServer.takeRequest().body.readUtf8()

        assertTrue(body.startsWith("[") && body.endsWith("]"), "Body must be a bare JSON array")
        // SensorId: first 8 bytes of raw sensor ID reversed, uppercase
        // rawSensorId="abc123" → bytes [ab][c1][23] (only 3 bytes) → reversed → [23][c1][ab] → "23C1AB"
        val expectedSensorId = "abc123".chunked(2).take(8).reversed().joinToString("").uppercase()
        assertTrue(body.contains("\"SensorId\":\"$expectedSensorId\""), "SensorId mismatch, got: $body")
        assertTrue(body.contains("\"TransmitterId\":\"TXSERIAL\""), "Missing TransmitterId")
        assertTrue(body.contains("\"CurrentGlucoseValue\":95"), "Missing CurrentGlucoseValue")
        assertTrue(body.contains("\"FWVersion\":\"2.0.1\""), "Missing FWVersion")
        // EssentialLog: base64-encoded bytes (server expects System.Byte[] / base64)
        val expectedBase64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte()))
        assertTrue(body.contains("\"EssentialLog\":\"$expectedBase64\""), "EssentialLog must be base64, got: $body")
    }

    @Test
    fun `uploadGlucoseReadings sends multiple readings in one request`() {
        val futureExpiry = System.currentTimeMillis() + 600_000L
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(futureExpiry)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("token")

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val readings = listOf(
            EversenseCGMResult(100, 1700000000000L, EversenseTrendArrow.FLAT, "s1", "aa"),
            EversenseCGMResult(110, 1700000300000L, EversenseTrendArrow.SINGLE_UP, "s1", "bb"),
            EversenseCGMResult(105, 1700000600000L, EversenseTrendArrow.SINGLE_DOWN, "s1", "cc")
        )

        EversenseHttp365Util.uploadGlucoseReadings(prefs, readings, "TX99", "3.0")

        assertEquals(1, mockWebServer.requestCount)
        val body = mockWebServer.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"CurrentGlucoseValue\":100"))
        assertTrue(body.contains("\"CurrentGlucoseValue\":110"))
        assertTrue(body.contains("\"CurrentGlucoseValue\":105"))
    }

    @Test
    fun `uploadGlucoseReadings does nothing when readings list is empty`() {
        EversenseHttp365Util.uploadGlucoseReadings(prefs, emptyList(), "TX99", "1.0")

        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun `uploadGlucoseReadings does not throw on 4xx server error`() {
        val futureExpiry = System.currentTimeMillis() + 600_000L
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(futureExpiry)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("token")

        mockWebServer.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad request"}"""))

        val readings = listOf(
            EversenseCGMResult(120, System.currentTimeMillis(), EversenseTrendArrow.FLAT, "s1", "ff")
        )

        // Should not throw — errors are logged internally, returns false
        val result = EversenseHttp365Util.uploadGlucoseReadings(prefs, readings, "TX1", "1.0")

        assertFalse(result, "Expected upload to return false on HTTP 400")
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `uploadGlucoseReadings does not throw on 5xx server error`() {
        val futureExpiry = System.currentTimeMillis() + 600_000L
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(futureExpiry)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("token")

        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val readings = listOf(
            EversenseCGMResult(80, System.currentTimeMillis(), EversenseTrendArrow.FLAT, "s1", "01")
        )

        val result = EversenseHttp365Util.uploadGlucoseReadings(prefs, readings, "TX1", "1.0")

        assertFalse(result, "Expected upload to return false on HTTP 500")
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `uploadGlucoseReadings skips upload when no valid token available`() {
        // Token expired and login fails
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(0L)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn(null)

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        val readings = listOf(
            EversenseCGMResult(100, System.currentTimeMillis(), EversenseTrendArrow.FLAT, "s1", "ab")
        )

        EversenseHttp365Util.uploadGlucoseReadings(prefs, readings, "TX1", "1.0")

        // Only the login attempt should have been made, not the upload
        assertEquals(1, mockWebServer.requestCount)
        assertEquals("/connect/token", mockWebServer.takeRequest().path)
    }

    // ─── putCurrentValues ────────────────────────────────────────────────────

    @Test
    fun `putCurrentValues posts to correct portal endpoint with correct JSON`() {
        val futureExpiry = System.currentTimeMillis() + 600_000L
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(futureExpiry)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("portal_token")

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("true"))

        val result = EversenseHttp365Util.putCurrentValues(
            preferences = prefs,
            glucose = 110,
            timestamp = 1700000000000L,
            trend = EversenseTrendArrow.FLAT,
            signalStrength = 2,
            batteryPercentage = 80
        )

        assertTrue(result)
        assertEquals(1, mockWebServer.requestCount)
        val request = mockWebServer.takeRequest()
        assertEquals("/api/care/PutCurrentValues", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer portal_token", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"CurrentGlucose\":110"), "Missing CurrentGlucose: $body")
        assertTrue(body.contains("\"GlucoseTrend\":3"), "FLAT should map to ordinal 3: $body")
        assertTrue(body.contains("\"BatteryStrength\":80"), "Missing BatteryStrength: $body")
        assertTrue(body.contains("\"IsTransmitterConnected\":1"), "Missing IsTransmitterConnected: $body")
    }

    @Test
    fun `putCurrentValues returns false on 4xx`() {
        val futureExpiry = System.currentTimeMillis() + 600_000L
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(futureExpiry)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("portal_token")

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val result = EversenseHttp365Util.putCurrentValues(
            preferences = prefs,
            glucose = 100,
            timestamp = System.currentTimeMillis(),
            trend = EversenseTrendArrow.FLAT,
            signalStrength = 0,
            batteryPercentage = 50
        )

        assertFalse(result)
    }

    // ─── login ───────────────────────────────────────────────────────────────

    @Test
    fun `login sends correct form-encoded body`() {
        whenever(prefs.getString(StorageKeys.SECURE_STATE, null)).thenReturn(
            """{"username":"testuser@test.com","password":"secret123"}"""
        )

        mockWebServer.enqueue(MockResponse().setBody(validTokenJson).setResponseCode(200))

        val result = EversenseHttp365Util.login(prefs)

        assertNotNull(result)
        assertEquals("test_access_token_abc123", result!!.access_token)
        assertEquals(3600, result.expires_in)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("grant_type=password"))
        assertTrue(body.contains("client_id=eversenseMMAAndroid"))
        assertTrue(body.contains("username=testuser%40test.com"), "Username must be URL-encoded: $body")
    }

    @Test
    fun `login returns null on 401 response`() {
        whenever(prefs.getString(StorageKeys.SECURE_STATE, null)).thenReturn(
            """{"username":"bad@user.com","password":"wrong"}"""
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_grant"}"""))

        val result = EversenseHttp365Util.login(prefs)

        assertNull(result)
    }

    // ─── putDeviceEvents ─────────────────────────────────────────────────────

    @Test
    fun `putDeviceEvents posts to correct endpoint with bearer token`() {
        val futureExpiry = System.currentTimeMillis() + 600_000L
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(futureExpiry)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("dev_token")

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val readings = listOf(
            EversenseCGMResult(
                glucoseInMgDl = 120,
                datetime = 1700000000000L,
                trend = EversenseTrendArrow.FLAT,
                sensorId = "14141713872a3c5818e0",
                rawResponseHex = "deadbeef"
            )
        )

        val result = EversenseHttp365Util.putDeviceEvents(prefs, readings, "313576")

        assertTrue(result, "Expected putDeviceEvents to return true on HTTP 200")
        assertEquals(1, mockWebServer.requestCount)
        val request = mockWebServer.takeRequest()
        assertEquals("/api/care/PutDeviceEvents", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer dev_token", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Content-Type"))
    }

    @Test
    fun `putDeviceEvents sends correct JSON fields`() {
        val futureExpiry = System.currentTimeMillis() + 600_000L
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(futureExpiry)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("dev_token")

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val readings = listOf(
            EversenseCGMResult(
                glucoseInMgDl = 110,
                datetime = 1700000000000L,
                trend = EversenseTrendArrow.FLAT,
                sensorId = "14141713872a3c5818e0",
                rawResponseHex = "aabb"
            )
        )

        EversenseHttp365Util.putDeviceEvents(prefs, readings, "313576")

        val body = mockWebServer.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"deviceType\":\"SMSIMeter\""), "Missing deviceType: $body")
        assertTrue(body.contains("\"deviceName\":\"Smart Transmitter (Android)\""), "Missing deviceName: $body")
        assertTrue(body.contains("\"deviceID\":\"313576\""), "Missing deviceID: $body")
        assertTrue(body.contains("\"algorithmVersion\":\"10\""), "Missing algorithmVersion: $body")
        assertTrue(body.contains("\"sgBytes\":"), "Missing sgBytes: $body")
        assertTrue(body.contains("\"mgBytes\":"), "Missing mgBytes: $body")
        assertTrue(body.contains("\"patientBytes\":"), "Missing patientBytes: $body")
        assertTrue(body.contains("\"alertBytes\":"), "Missing alertBytes: $body")
        assertTrue(body.contains("\"offsetBytes\":"), "Missing offsetBytes: $body")
    }

    @Test
    fun `putDeviceEvents sgBytes decodes to binary containing correct glucose value`() {
        val futureExpiry = System.currentTimeMillis() + 600_000L
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(futureExpiry)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("dev_token")

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val glucose = 135
        val readings = listOf(
            EversenseCGMResult(
                glucoseInMgDl = glucose,
                datetime = 1700000000000L,
                trend = EversenseTrendArrow.FLAT,
                sensorId = "14141713872a3c5818e0",
                rawResponseHex = "aabb"
            )
        )

        EversenseHttp365Util.putDeviceEvents(prefs, readings, "TX001")

        val body = mockWebServer.takeRequest().body.readUtf8()
        // Extract sgBytes value from JSON
        val sgBytesMatch = Regex("\"sgBytes\":\"([^\"]+)\"").find(body)
        assertNotNull(sgBytesMatch, "sgBytes not found in body: $body")
        val sgBytes = java.util.Base64.getDecoder().decode(sgBytesMatch!!.groupValues[1])

        // Header: 8C 00 01 00 00 + count(3 bytes) — 8 bytes total
        assertEquals(0x8C.toByte(), sgBytes[0], "First byte must be 0x8C")
        // Count = 1 reading → 3-byte LE = 01 00 00 at bytes 5-7
        assertEquals(1, sgBytes[5].toInt() and 0xFF, "Count low byte must be 1")

        // Glucose at bytes 11-12 (after header(8) + recNo(3) + date(2) - 1 = offset 10): LE 16-bit
        // Header=8, recNo=3, date=2, time=2 → glucose starts at byte 15
        val glucoseLow = sgBytes[15].toInt() and 0xFF
        val glucoseHigh = sgBytes[16].toInt() and 0xFF
        val decodedGlucose = glucoseLow or (glucoseHigh shl 8)
        assertEquals(glucose, decodedGlucose, "Glucose value in sgBytes mismatch")
    }

    @Test
    fun `putDeviceEvents does nothing when readings list is empty`() {
        EversenseHttp365Util.putDeviceEvents(prefs, emptyList(), "TX001")
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun `putDeviceEvents returns false on 4xx`() {
        val futureExpiry = System.currentTimeMillis() + 600_000L
        whenever(prefs.getLong(StorageKeys.ACCESS_TOKEN_EXPIRY, 0)).thenReturn(futureExpiry)
        whenever(prefs.getString(StorageKeys.ACCESS_TOKEN, null)).thenReturn("dev_token")

        mockWebServer.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad request"}"""))

        val readings = listOf(
            EversenseCGMResult(100, System.currentTimeMillis(), EversenseTrendArrow.FLAT, "aabb", "ff")
        )

        val result = EversenseHttp365Util.putDeviceEvents(prefs, readings, "TX001")
        assertFalse(result, "Expected false on HTTP 400")
    }
}
