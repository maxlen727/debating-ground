package debating.ground.app

import debating.ground.app.ui.IconPaths
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// 图标和网页版用同一组线条路径（src/components/ui.tsx 的 PATHS）
class IconPathsTest {
    private val web: Map<String, String> =
        Regex("""^\s+(\w+): '([^']+)',?$""", RegexOption.MULTILINE)
            .findAll(File(System.getProperty("web.icons")!!).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test fun nativeIconsUseTheWebPaths() {
        val native = mapOf(
            "back" to IconPaths.BACK,
            "chevron" to IconPaths.CHEVRON,
            "learn" to IconPaths.LEARN,
            "chat" to IconPaths.CHAT,
            "library" to IconPaths.LIBRARY,
            "settings" to IconPaths.SETTINGS,
        )
        for ((name, path) in native) assertEquals("图标 $name", web[name] ?: error("ui.tsx 里没有图标 $name"), path)
    }
}
