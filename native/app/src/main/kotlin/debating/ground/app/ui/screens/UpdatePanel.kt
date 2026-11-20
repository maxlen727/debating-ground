package debating.ground.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.Panel
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.SecondaryButton
import debating.ground.app.ui.SectionLabel
import debating.ground.app.ui.Segmented
import debating.ground.app.ui.StatusTag
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color
import debating.ground.app.update.UpdatePrefs
import debating.ground.app.update.Verdict
import debating.ground.app.update.fetchLatest
import debating.ground.app.update.installedVersion
import debating.ground.app.update.verdictOf
import kotlinx.coroutines.launch

// 设置里的“版本与更新”：手动检查，或者打开“启动时自动检查”（一天最多查一次，默认关闭）
@Composable
fun UpdatePanel() {
    val t = LocalTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { installedVersion(context) }
    var auto by remember { mutableStateOf(UpdatePrefs.auto(context)) }
    var checking by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<Verdict?>(null) }
    var problem by remember { mutableStateOf("") }
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("版本与更新") }
            StatusTag(if (verdict is Verdict.Newer) "有新版本" else "当前 $installed", if (verdict is Verdict.Newer) "warn" else "")
        }
        when (val v = verdict) {
            is Verdict.Newer -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("新版本 ${v.release.version} 已经发布", style = TextStyles.body.copy(fontWeight = FontWeight.SemiBold), color = t.ink.color())
                if (v.release.notes.isNotEmpty()) {
                    Text(v.release.notes.lines().filter { it.isNotBlank() }.take(6).joinToString("\n"), style = TextStyles.small, color = t.muted.color())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("更新说明", { open(v.release.pageUrl) })
                    PrimaryButton("下载新版本", { open(v.release.apkUrl ?: v.release.pageUrl) }, Modifier.weight(1f))
                }
            }

            is Verdict.UpToDate -> Text("已经是最新的正式版。", style = TextStyles.small, color = t.good.color())

            is Verdict.Preview -> Text("你装的是预览版（$installed），比最新的正式版 ${v.release.version} 还新。", style = TextStyles.small, color = t.muted.color())

            null -> {}
        }
        if (problem.isNotEmpty()) Text(problem, style = TextStyles.small, color = t.bad.color())
        SecondaryButton(if (checking) "正在检查…" else "检查更新", {
            checking = true
            problem = ""
            scope.launch {
                runCatching { fetchLatest() }
                    .onSuccess { verdict = verdictOf(installed, it) }
                    .onFailure { problem = it.message ?: "没能查到最新版本，检查一下网络再试。" }
                checking = false
            }
        }, Modifier.fillMaxWidth(), enabled = !checking)
        Segmented(listOf("off" to "手动检查", "on" to "启动时自动检查"), if (auto) "on" else "off", "检查更新的方式") { id ->
            auto = id == "on"
            UpdatePrefs.setAuto(context, auto)
        }
        FinePrint("检查更新只访问 GitHub 上思辨场的发布页，不带你的任何数据。下载后系统会问要不要安装；新版本和现在的签名相同，直接覆盖安装，知识库不会丢。")
    }
}
