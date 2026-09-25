package com.example.gatewayservice.admin

import org.springframework.cloud.gateway.config.GatewayProperties
import org.springframework.cloud.gateway.config.GlobalCorsProperties
import org.springframework.cloud.gateway.support.NameUtils
import java.time.Instant

/** 게이트웨이 설정 한 벌. 백오피스(modu-system)의 '게이트웨이 설정' 화면이 그대로 그린다. */
data class GatewayConfigResponse(
    val routes: List<RouteView>,
    val defaultFilters: List<DefinitionView>,
    val cors: List<CorsView>,
    /** 이 응답을 만든 시각(UTC ISO-8601). */
    val generatedAt: String,
)

data class RouteView(
    val id: String?,
    val uri: String?,
    val order: Int,
    val predicates: List<DefinitionView>,
    val filters: List<DefinitionView>,
    val access: AccessView,
)

/** 술어·필터 하나. args 는 설정에 적힌 순서의 값이다(단축 인자는 값만, 이름 있는 인자는 `key=value`). */
data class DefinitionView(
    val name: String,
    val args: List<String>,
)

/** 누가 부를 수 있는가. AuthorizationHeaderFilter 가 있으면 PROTECTED(역할·audience), 없으면 PUBLIC. */
data class AccessView(
    val type: AccessType,
    val role: String?,
    val audience: String?,
)

enum class AccessType { PUBLIC, PROTECTED }

data class CorsView(
    val pattern: String,
    val allowedOrigins: List<String>,
    val allowedMethods: List<String>,
    val allowedHeaders: List<String>,
    val allowCredentials: Boolean?,
)

object GatewayConfigView {
    private const val AUTH_FILTER = "AuthorizationHeaderFilter"
    private const val DEFAULT_ROLE = "ROLE_USER"

    fun of(gateway: GatewayProperties, cors: GlobalCorsProperties, now: Instant = Instant.now()) =
        GatewayConfigResponse(
            routes = gateway.routes.map { route ->
                val filters = route.filters.map { DefinitionView(it.name, maskSecrets(it.name, argsOf(it.args))) }
                RouteView(
                    id = route.id,
                    uri = route.uri?.toString(),
                    order = route.order,
                    predicates = route.predicates.map { DefinitionView(it.name, argsOf(it.args)) },
                    filters = filters,
                    access = accessOf(route.filters.firstOrNull { it.name == AUTH_FILTER }?.args),
                )
            },
            defaultFilters = gateway.defaultFilters.map { DefinitionView(it.name, maskSecrets(it.name, argsOf(it.args))) },
            cors = cors.corsConfigurations.map { (pattern, c) ->
                CorsView(
                    pattern = pattern,
                    allowedOrigins = c.allowedOrigins.orEmpty() + c.allowedOriginPatterns.orEmpty(),
                    allowedMethods = c.allowedMethods.orEmpty(),
                    allowedHeaders = c.allowedHeaders.orEmpty(),
                    allowCredentials = c.allowCredentials,
                )
            },
            generatedAt = now.toString(),
        )

    /**
     * `Path=/a,/b` 처럼 쉼표로 적은 단축 인자는 스프링이 `_genkey_0`, `_genkey_1` … 로 이미 나눠 둔다.
     * yml 에서 속성 치환을 피하려고 적은 `$\{segment}` 는 게이트웨이(RewritePath)가 실행 때 `${segment}` 로 푼다. 보여 줄 때도 푼 값으로.
     */
    fun argsOf(args: Map<String, String>): List<String> =
        args.entries.map { (key, raw) ->
            val value = raw.replace("\$\\", "\$")
            if (key.startsWith(NameUtils.GENERATED_NAME_PREFIX)) value else "$key=$value"
        }

    const val MASK = "******"

    /** 값을 넣는 헤더 필터들. 첫 인자가 헤더 이름, 둘째가 값이다. */
    private val HEADER_VALUE_FILTERS = setOf(
        "AddRequestHeader", "SetRequestHeader", "AddResponseHeader", "SetResponseHeader", "AddRequestHeadersIfNotPresent",
    )
    private val SECRET_NAME = Regex("token|secret|password|authorization|api-?key|credential", RegexOption.IGNORE_CASE)

    /**
     * 비밀 값을 가린다. 예: `AddRequestHeader=X-Internal-Token,<서비스 간 토큰>` 의 토큰 값.
     * 헤더 이름이 비밀처럼 보이면 값을, `key=value` 인자는 key 가 비밀처럼 보이면 value 를 [MASK] 로 바꾼다.
     */
    fun maskSecrets(filterName: String, args: List<String>): List<String> {
        val headerName = args.firstOrNull()
        val maskHeaderValue = filterName in HEADER_VALUE_FILTERS && headerName != null && SECRET_NAME.containsMatchIn(headerName)
        return args.mapIndexed { i, arg ->
            val key = arg.substringBefore('=', missingDelimiterValue = "")
            when {
                maskHeaderValue && i == 1 -> MASK
                key.isNotEmpty() && SECRET_NAME.containsMatchIn(key) -> "$key=$MASK"
                else -> arg
            }
        }
    }

    /** AuthorizationHeaderFilter 인자: 단축형은 role, audience 순서, 이름형은 role=/audience=. role 기본값은 ROLE_USER. */
    fun accessOf(args: Map<String, String>?): AccessView {
        if (args == null) return AccessView(AccessType.PUBLIC, null, null)
        val shortcut = args.filterKeys { it.startsWith(NameUtils.GENERATED_NAME_PREFIX) }.values.toList()
        val role = args["role"] ?: shortcut.getOrNull(0)
        val audience = args["audience"] ?: shortcut.getOrNull(1)
        return AccessView(AccessType.PROTECTED, role?.takeIf { it.isNotBlank() } ?: DEFAULT_ROLE, audience?.takeIf { it.isNotBlank() })
    }
}
