package dev.apkcli.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KiroCredentialTest {

    @Test
    fun `a bare refresh token is a social sign-in, anything else unshaped is a key`() {
        assertEquals(KiroCredential.Social("aorAAAAAGabc"), KiroCredential.parse("  aorAAAAAGabc \n"))
        assertEquals(KiroCredential.ApiKey("ksk_live_123"), KiroCredential.parse("ksk_live_123"))
        assertFalse(KiroCredential.isStructured("aorAAAAAGabc"))
    }

    @Test
    fun `Kiro IDE's token file pastes as it is for a social sign-in`() {
        val file = """
            {
              "accessToken": "aoaAAAAAGaccess",
              "refreshToken": "aorAAAAAGrefresh",
              "expiresAt": "2026-10-09T10:00:00.000Z",
              "authMethod": "social",
              "provider": "Google",
              "profileArn": "arn:aws:codewhisperer:us-east-1:699475941385:profile/EHGA3GRVQMUK"
            }
        """.trimIndent()
        val parsed = KiroCredential.parse(file)
        assertEquals(
            KiroCredential.Social("aorAAAAAGrefresh", "arn:aws:codewhisperer:us-east-1:699475941385:profile/EHGA3GRVQMUK"),
            parsed,
        )
        assertTrue(KiroCredential.isStructured(file))
    }

    @Test
    fun `a Builder ID sign-in carries its client, in any of the manager's shapes`() {
        val expected = KiroCredential.Oidc("aorBuilder", "cid", "csecret", region = "eu-central-1", provider = "BuilderId")
        assertEquals(
            expected,
            KiroCredential.parse("""{"refreshToken":"aorBuilder","clientId":"cid","clientSecret":"csecret","region":"eu-central-1","provider":"BuilderId"}"""),
        )
        assertEquals(
            "an export is a list, the credentials nested",
            expected,
            KiroCredential.parse("""[{"email":"a@b.c","credentials":{"refreshToken":"aorBuilder","clientId":"cid","clientSecret":"csecret","region":"eu-central-1","provider":"BuilderId"}}]"""),
        )
        assertEquals(
            KiroCredential.Oidc("tok", "cid", "csecret", provider = "BuilderId"),
            KiroCredential.parse("me@example.com----no_password----tok----cid----csecret"),
        )
        assertEquals(KiroCredential.Social("tok"), KiroCredential.parse("me@example.com\tpw\ttok"))
    }

    @Test
    fun `an organisation's sign-in is told apart, and a token without its client is said to be one`() {
        val enterprise = KiroCredential.parse("""{"refreshToken":"t","clientId":"c","clientSecret":"s","provider":"Enterprise"}""") as KiroCredential.Oidc
        assertTrue(enterprise.enterprise)
        assertFalse(enterprise.externalIdp)
        val external = KiroCredential.parse("""{"refreshToken":"t","clientId":"c","clientSecret":"s","authMethod":"external_idp"}""") as KiroCredential.Oidc
        assertTrue(external.externalIdp)

        assertEquals(KiroCredential.MissingClient("t"), KiroCredential.parse("""{"refreshToken":"t","authMethod":"IdC","provider":"BuilderId"}"""))
        assertTrue("JSON without a token is nothing to exchange", KiroCredential.parse("""{"accessToken":"x"}""") is KiroCredential.ApiKey)
    }

    @Test
    fun `what is stored reads back the same, rotated token and all`() {
        val oidc = KiroCredential.Oidc("t1", "cid", "csecret", region = "us-east-1", provider = "Enterprise", profileArn = "arn:x")
        val stored = KiroCredential.encode(oidc.withRefreshToken("t2"))
        assertFalse("one line", stored.contains('\n'))
        assertEquals(oidc.copy(refreshToken = "t2"), KiroCredential.parse(stored))

        assertEquals("a bare token stays bare", "aorAAAAAGx", KiroCredential.normalize(" aorAAAAAGx "))
        assertEquals(KiroCredential.Social("aorY", "arn:y"), KiroCredential.parse(KiroCredential.encode(KiroCredential.Social("aorY", "arn:y"))))
    }

    @Test
    fun `each connection has its own stable device id`() {
        val id = KiroAuth.machineId("ep_1")
        assertEquals(64, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(id, KiroAuth.machineId("ep_1"))
        assertNotEquals(id, KiroAuth.machineId("ep_2"))
    }

    @Test
    fun `the client marker is Kiro IDE's, with the device id`() {
        val agent = KiroIdentity.userAgent("abc")
        assertTrue(agent.startsWith("aws-sdk-js/1.0.34 ua/2.1 os/linux#"))
        assertTrue(agent.endsWith(" api/codewhispererstreaming#1.0.34 m/E KiroIDE-0.12.155-abc"))
        assertEquals("aws-sdk-js/1.0.34 KiroIDE 0.12.155 abc", KiroIdentity.amzUserAgent("abc"))
        assertEquals("eu-central-1", KiroAuth.serviceRegion("eu-west-1"))
        assertEquals("us-east-1", KiroAuth.serviceRegion("us-west-2"))
    }
}
