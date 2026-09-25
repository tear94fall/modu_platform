package com.example.configservice.admin

import org.springframework.boot.env.PropertiesPropertySourceLoader
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.origin.OriginLookup
import org.springframework.boot.origin.TextResourceOrigin
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * config-repo 파일을 관리 화면용으로 읽는다.
 *
 * 설정 서버의 `/{name}/{profile}` 응답은 `{cipher}` 값을 이미 풀어 둔 상태라 비밀값을 이름으로만 짐작해 가려야 한다.
 * 여기서는 파일을 직접 읽으므로 복호화 전 값이 보이고, 암호화된 값은 확실히 알아보고 가릴 수 있다.
 * 이름이 비밀값처럼 생긴 키(password, secret, token, key, hash …)와 URL 안의 비밀번호, PEM 키도 한 번 더 가린다.
 *
 * @param roots 설정 서버의 search-locations 순서. 첫 번째를 기준으로 상대 경로를 붙인다.
 */
class ConfigRepoReader(private val roots: List<Path>) {

    private val base: Path? = roots.firstOrNull()

    /** 설정 서버가 읽는 파일 목록. 폴더는 search-locations 하나당 한 단계만 본다(설정 서버와 같다). */
    fun files(): List<ConfigFileSummary> =
        entries().map { (path, file) ->
            ConfigFileSummary(
                path = path,
                group = path.substringBeforeLast('/', missingDelimiterValue = ""),
                name = file.name,
                size = Files.size(file),
                modifiedAt = Files.getLastModifiedTime(file).toInstant(),
            )
        }

    /** 목록에 있는 경로만 읽는다. 사용자가 보낸 경로를 파일 시스템에 그대로 넘기지 않는다. */
    fun read(path: String): ConfigFileView? {
        val file = entries().firstOrNull { it.first == path }?.second ?: return null
        val loader = if (file.extension == "properties") PropertiesPropertySourceLoader() else YamlPropertySourceLoader()
        val sources = loader.load(path, FileSystemResource(file))
        return ConfigFileView(
            path = path,
            documents = sources.mapIndexed { index, source -> document(index, source) },
        )
    }

    private fun document(index: Int, source: org.springframework.core.env.PropertySource<*>): ConfigDocument {
        val names = (source as? EnumerablePropertySource<*>)?.propertyNames?.toList().orEmpty()
        val properties = names.map { key ->
            @Suppress("UNCHECKED_CAST")
            val origin = (source as? OriginLookup<String>)?.getOrigin(key)
            val line = (origin as? TextResourceOrigin)?.location?.line?.plus(1)
            val value = source.getProperty(key)
            SecretMasker.mask(key, value?.toString() ?: "").let { (shown, protection) ->
                ConfigProperty(key = key, value = shown, line = line, protection = protection)
            }
        }
        val activateOn = properties.firstOrNull { it.key == "spring.config.activate.on-profile" || it.key == "spring.profiles" }?.value
        return ConfigDocument(index = index, activateOn = activateOn, properties = properties)
    }

    /** (표시 경로, 실제 파일). 표시 경로는 첫 search-location 기준 상대 경로라 "messenger/messenger.yml" 처럼 보인다. */
    private fun entries(): List<Pair<String, Path>> =
        roots.filter { Files.isDirectory(it) }
            .flatMap { root ->
                Files.list(root).use { stream ->
                    stream.filter { it.isRegularFile() && it.extension in EXTENSIONS }.toList()
                }
            }
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .map { file -> displayPath(file) to file }
            .sortedWith(compareBy({ it.first.contains('/') }, { it.first }))

    private fun displayPath(file: Path): String {
        val root = base?.toAbsolutePath()?.normalize()
        return if (root != null && file.startsWith(root)) root.relativize(file).joinToString("/") else file.name
    }

    companion object {
        private val EXTENSIONS = setOf("yml", "yaml", "properties")

        /** `file:/config-repo, file:/config-repo/messenger` 같은 search-locations 를 폴더 경로로 바꾼다. file: 이 아닌 위치는 건너뛴다. */
        fun fromLocations(locations: Array<String>): ConfigRepoReader =
            ConfigRepoReader(
                locations.map { it.trim() }
                    .filter { it.startsWith("file:") }
                    .map { Path.of(it.removePrefix("file:").removeSuffix("/")) },
            )
    }
}

data class ConfigFileSummary(
    val path: String,
    /** 공통 파일은 "", 제품별 폴더 파일은 폴더 이름(messenger, commerce). */
    val group: String,
    val name: String,
    val size: Long,
    val modifiedAt: Instant,
)

data class ConfigFileView(val path: String, val documents: List<ConfigDocument>)

/** YAML 의 `---` 로 나뉜 문서 하나. [activateOn] 은 그 문서가 켜지는 프로필. */
data class ConfigDocument(val index: Int, val activateOn: String?, val properties: List<ConfigProperty>)

data class ConfigProperty(val key: String, val value: String, val line: Int?, val protection: Protection)

enum class Protection {
    /** 그대로 보여 준다. */
    NONE,

    /** `{cipher}` 로 암호화된 값. */
    ENCRYPTED,

    /** 키 이름이나 값 모양이 비밀값이라 통째로 가렸다. */
    SECRET,

    /** URL 안의 비밀번호처럼 일부만 가렸다. */
    PARTIAL,
}

internal object SecretMasker {
    const val MASK = "******"

    /** 키 마지막 부분의 마지막 단어가 이것이면 비밀값이다. access-token-ttl 은 ttl 로 끝나 가리지 않는다. */
    private val SECRET_WORDS = setOf(
        "password", "passwd", "pwd", "secret", "token", "key", "hash", "credential", "credentials", "passphrase",
    )
    private val URL_USERINFO = Regex("(://[^:/@\\s]+:)([^@\\s]+)(@)")

    fun mask(key: String, value: String): Pair<String, Protection> {
        if (value.startsWith("{cipher}")) return MASK to Protection.ENCRYPTED
        if (value.isNotEmpty() && isSecretKey(key)) return MASK to Protection.SECRET
        if (value.contains("-----BEGIN")) return MASK to Protection.SECRET
        if (URL_USERINFO.containsMatchIn(value)) return value.replace(URL_USERINFO, "$1$MASK$3") to Protection.PARTIAL
        return value to Protection.NONE
    }

    fun isSecretKey(key: String): Boolean {
        val last = key.substringAfterLast('.').replace(Regex("\\[\\d+]"), "")
        val words = last.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase().split('-', '_').filter { it.isNotEmpty() }
        return words.lastOrNull() in SECRET_WORDS || words.any { it == "secret" || it == "password" }
    }
}
