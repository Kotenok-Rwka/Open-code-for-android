package com.pocketcode

import com.pocketcode.llm.ModelRouter
import com.pocketcode.ui.LocalhostWidgets
import com.pocketcode.ui.OauthHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondWaveTest {
    @Test
    fun rolesPersistInSettings() {
        val s = Settings(planningProfileId = "plan", executionProfileId = "exec", routerBaseUrl = "http://127.0.0.1:8080")
        assertEquals("plan", s.planningProfileId)
        assertEquals("exec", s.executionProfileId)
        assertTrue(ModelRouter.isRouterAvailable(s.routerBaseUrl))
        assertFalse(ModelRouter.isRouterAvailable("ftp://x"))
    }

    @Test
    fun routerParsesModels() {
        val json = """{"data":[{"id":"m1","owned_by":"local"},{"id":"m2"}]}"""
        val models = ModelRouter.list("http://127.0.0.1:1234", "key") { url, headers ->
            assert(url.endsWith("/v1/models"))
            assert(headers["Authorization"] == "Bearer key")
            json
        }
        assertEquals(2, models.size)
        assertEquals("m1", models[0].id)
    }

    @Test
    fun onlyLocalHostsAllowedForWidgets() {
        assertTrue(LocalhostWidgets.isLocal("http://127.0.0.1:8765/"))
        assertTrue(LocalhostWidgets.isLocal("http://192.168.1.10:8080"))
        assertFalse(LocalhostWidgets.isLocal("https://example.com"))
        assertEquals(3, LocalhostWidgets.defaults().size)
    }

    @Test
    fun oauthUsesPkceAndBrowserUrl() {
        val flow = OauthHelper.begin("https://example.com/authorize", "client", "pocketcode://oauth", "openid profile")
        assertTrue(flow.authorizeUrl.contains("code_challenge_method=S256"))
        assertTrue(flow.authorizeUrl.contains("client_id=client"))
        assertTrue(flow.codeVerifier.length >= 40)
        assertTrue(flow.state.length >= 20)
    }

    @Test
    fun cavemanSkillsPresent() {
        assertEquals(5, Caveman.skills.size)
        assertTrue(Caveman.prompt(true).contains("Caveman"))
        assertEquals("", Caveman.prompt(false))
    }
}
