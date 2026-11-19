package com.example.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PcProfileTest {
    private fun json(url: String) = JSONObject().put("version", 1).put("url", url)
        .put("token", "a".repeat(48)).put("caPem", "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----").toString()

    @Test fun acceptsHttpsOrigin() {
        assertEquals("https://pc.example:8443", PcProfile.parse(json("https://pc.example:8443/")).url)
    }
    @Test fun acceptsUtf8BomFromTextEditor() {
        assertEquals("https://pc.example:8443", PcProfile.parse("\uFEFF" + json("https://pc.example:8443")).url)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsCleartext() { PcProfile.parse(json("http://pc.example")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsCredentialsInUrl() { PcProfile.parse(json("https://user:password@pc.example")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsPath() { PcProfile.parse(json("https://pc.example/api")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsQuery() { PcProfile.parse(json("https://pc.example/?token=secret")) }
}
