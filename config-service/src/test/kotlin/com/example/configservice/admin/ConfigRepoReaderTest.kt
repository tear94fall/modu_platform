package com.example.configservice.admin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigRepoReaderTest {

    @TempDir
    lateinit var repo: Path

    private fun reader(): ConfigRepoReader {
        repo.resolve("application.yml").writeText(
            """
            modu:
              internal-api:
                token: plain-internal-token
              oauth:
                access-token-ttl: 30m
                private-key: |
                  -----BEGIN PRIVATE KEY-----
                  abc
                  -----END PRIVATE KEY-----
            eureka:
              client:
                service-url:
                  defaultZone: http://discovery-service:8761/eureka
            """.trimIndent(),
        )
        repo.resolve("messenger").createDirectories().resolve("messenger.yml").writeText(
            """
            spring:
              datasource:
                url: jdbc:mysql://mysql:3306/modu
                username: modu
                password: '{cipher}abcdef0123'
              redis:
                url: redis://admin:hunter2@redis:6379
            ---
            spring:
              config:
                activate:
                  on-profile: local
              datasource:
                url: jdbc:mysql://localhost:3306/modu
            """.trimIndent(),
        )
        repo.resolve("messenger").resolve("notes.txt").writeText("not config")
        return ConfigRepoReader.fromLocations(arrayOf("file:$repo", "file:$repo/messenger/", "classpath:/ignored"))
    }

    @Test
    fun `lists files from every search location with paths relative to the first`() {
        val files = reader().files()

        assertEquals(listOf("application.yml", "messenger/messenger.yml"), files.map { it.path })
        assertEquals(listOf("", "messenger"), files.map { it.group })
        assertEquals("messenger.yml", files[1].name)
    }

    @Test
    fun `masks encrypted values, secret names, PEM keys and URL passwords`() {
        val common = reader().read("application.yml")!!.documents.single().properties.associateBy { it.key }

        assertEquals(SecretMasker.MASK, common.getValue("modu.internal-api.token").value)
        assertEquals(Protection.SECRET, common.getValue("modu.internal-api.token").protection)
        assertEquals(SecretMasker.MASK, common.getValue("modu.oauth.private-key").value)
        assertEquals("30m", common.getValue("modu.oauth.access-token-ttl").value)
        assertEquals(Protection.NONE, common.getValue("eureka.client.service-url.defaultZone").protection)

        val messenger = reader().read("messenger/messenger.yml")!!.documents[0].properties.associateBy { it.key }
        assertEquals(Protection.ENCRYPTED, messenger.getValue("spring.datasource.password").protection)
        assertEquals(SecretMasker.MASK, messenger.getValue("spring.datasource.password").value)
        assertEquals("modu", messenger.getValue("spring.datasource.username").value)
        assertEquals("redis://admin:******@redis:6379", messenger.getValue("spring.redis.url").value)
        assertEquals(Protection.PARTIAL, messenger.getValue("spring.redis.url").protection)
    }

    @Test
    fun `never returns a raw secret anywhere in the file view`() {
        val text = listOf("application.yml", "messenger/messenger.yml").joinToString { reader().read(it).toString() }

        listOf("plain-internal-token", "abcdef0123", "hunter2", "BEGIN PRIVATE KEY").forEach {
            assertFalse(text.contains(it), "leaked: $it")
        }
    }

    @Test
    fun `splits yaml documents and reports the profile and line`() {
        val docs = reader().read("messenger/messenger.yml")!!.documents

        assertEquals(2, docs.size)
        assertNull(docs[0].activateOn)
        assertEquals("local", docs[1].activateOn)
        assertEquals(3, docs[0].properties.first { it.key == "spring.datasource.url" }.line)
    }

    @Test
    fun `reads only listed files`() {
        val reader = reader()
        Files.writeString(repo.resolve("secret.env"), "X=1")

        assertNull(reader.read("../etc/passwd"))
        assertNull(reader.read("messenger/notes.txt"))
        assertNull(reader.read("secret.env"))
    }

    @Test
    fun `secret key names match on the last word only`() {
        listOf("password", "client-secret", "clients[0].secret", "private-key", "password-hash", "apiKey", "token", "secretValue")
            .forEach { assertTrue(SecretMasker.isSecretKey("a.$it"), it) }
        listOf("access-token-ttl", "username", "key-store-type", "url", "keys")
            .forEach { assertFalse(SecretMasker.isSecretKey("a.$it"), it) }
    }
}
