package com.example.deployservice.deploy

/**
 * modu_infra 의 kustomization.yaml 에서 `images:` 목록만 다룬다 — 글자 그대로 읽고 `newTag:` 값 한 군데만 바꾼다.
 * YAML 파서를 쓰지 않는 이유: 파일의 주석·들여쓰기·순서를 그대로 남겨야 사람이 쓴 설명(왜 커밋 태그로 고정했는지)이 사라지지 않는다.
 *
 * 다루는 모양(들여쓰기는 파일의 것을 따른다):
 * ```
 * images:
 *   - name: ghcr.io/tear94fall/modu-chat/storage-service
 *     newTag: develop-5708871  # 설명
 * ```
 */
object Kustomization {

    private val IMAGES_HEADER = Regex("""^images:\s*(#.*)?$""")
    private val LIST_ITEM = Regex("""^(\s*)-\s+(.*)$""")
    private val NAME_ITEM = Regex("""^(\s*)-\s+name:\s*(["']?)([^\s#"']+)\2\s*(#.*)?$""")
    private val NEW_TAG = Regex("""^(\s*newTag:\s*)(["']?)([^\s#"']+)\2(.*)$""")
    private val TOP_LEVEL = Regex("""^\S""")

    /** images[].name → newTag. newTag 가 없는 항목은 빠진다. */
    fun tags(content: String): Map<String, String> {
        val lines = content.lines()
        val block = imagesBlock(lines) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        var current: String? = null
        for (i in block) {
            val line = lines[i]
            val named = NAME_ITEM.matchEntire(line)
            when {
                named != null -> current = named.groupValues[3]
                LIST_ITEM.matches(line) -> current = null // name 이 아닌 다른 키로 시작하는 항목
                else -> {
                    val tag = NEW_TAG.matchEntire(line)?.groupValues?.get(3)
                    val name = current
                    if (tag != null && name != null) result[name] = tag
                }
            }
        }
        return result
    }

    /** [image] 의 newTag. images 에 없으면(base 의 태그를 그대로 쓰면) null. */
    fun currentTag(content: String, image: String): String? = tags(content)[image]

    /**
     * [image] 의 `newTag:` 값만 [newTag] 로 바꾼 전체 내용. 들여쓰기·뒤 주석·나머지 줄은 그대로다.
     * images 에 그 이미지가 없으면 목록 끝에 `- name` + `newTag` 두 줄을 덧붙인다(목록의 들여쓰기를 따른다).
     * images 블록 자체가 없으면 [IllegalArgumentException].
     */
    fun replaceTag(content: String, image: String, newTag: String): String {
        val lines = content.lines().toMutableList()
        val block = imagesBlock(lines) ?: throw IllegalArgumentException("kustomization.yaml 에 images: 목록이 없습니다.")

        val nameIndex = block.firstOrNull { NAME_ITEM.matchEntire(lines[it])?.groupValues?.get(3) == image }
        if (nameIndex != null) {
            for (i in (nameIndex + 1)..block.last) {
                val line = lines[i]
                if (LIST_ITEM.matches(line)) break
                val m = NEW_TAG.matchEntire(line) ?: continue
                val (prefix, quote, _, rest) = m.destructured
                lines[i] = "$prefix$quote$newTag$quote$rest"
                return lines.joinToString("\n")
            }
            // name 은 있는데 newTag 가 없다: name 줄 바로 아래에 넣는다.
            val indent = LIST_ITEM.matchEntire(lines[nameIndex])!!.groupValues[1]
            lines.add(nameIndex + 1, "$indent  newTag: $newTag")
            return lines.joinToString("\n")
        }

        // 이미지 항목이 없다: 마지막 항목(빈 줄·주석 제외) 뒤에 붙인다.
        val lastContent = block.lastOrNull { lines[it].isNotBlank() && !lines[it].trimStart().startsWith("#") } ?: block.first
        val indent = block.firstNotNullOfOrNull { LIST_ITEM.matchEntire(lines[it])?.groupValues?.get(1) } ?: "  "
        lines.add(lastContent + 1, "$indent  newTag: $newTag")
        lines.add(lastContent + 1, "$indent- name: $image")
        return lines.joinToString("\n")
    }

    /** `images:` 줄 다음부터 다음 최상위 키 직전까지의 줄 번호. 없으면 null. */
    private fun imagesBlock(lines: List<String>): IntRange? {
        val header = lines.indexOfFirst { IMAGES_HEADER.matches(it) }
        if (header < 0) return null
        var end = header
        for (i in (header + 1) until lines.size) {
            if (TOP_LEVEL.containsMatchIn(lines[i])) break
            end = i
        }
        // 블록 끝의 빈 줄은 다음 키 소속으로 본다(뒤에 덧붙일 때 빈 줄 앞에 들어가게).
        while (end > header && lines[end].isBlank()) end--
        return if (end > header) (header + 1)..end else null
    }
}
