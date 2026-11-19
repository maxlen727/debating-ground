package debating.ground.app.ui.screens

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import debating.ground.app.AppModel
import debating.ground.app.flow.AppState
import debating.ground.app.notify.REMINDER_TIMES
import debating.ground.app.notify.ReminderPrefs
import debating.ground.app.notify.Reminders
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.Chip
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.Input
import debating.ground.app.ui.Panel
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.QuietLink
import debating.ground.app.ui.SectionLabel
import debating.ground.app.ui.Segmented
import debating.ground.app.ui.StatusTag
import debating.ground.app.ui.TopBar
import debating.ground.app.ui.WithFloatingDock
import debating.ground.app.ui.scrollRow
import debating.ground.app.ui.scrollWithEdge
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color

// 设置：AI 来源。和网页版 src/screens/Settings.tsx 在 App 里的样子一致：演示模式或自己部署的后端。
// 原生 App 还多一种：直接连接服务商（密钥加密保存在设备上，见 AGENTS.md 约定 1）。朗读和语音输入不在原生版第一版里。

private const val DOCS = "https://maxlen727.github.io/debating-ground/guide/self-host"

@Composable
fun SettingsScreen(st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val busy = st.ui.busy != null
    val mode = st.config.aiMode.takeIf { it in setOf("remote", "api") } ?: "demo"
    // 连接时的进度和出错原因悬浮在底部，不被导航胶囊挡住
    WithFloatingDock(dock = {
        ErrorBanner(st.ui.error) { l.clearError() }
        BusyStrip(st.ui.busy) { l.cancelAI() }
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            TopBar("设置", "AI 来源与连接")
            Column(
                Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Panel {
                    SectionLabel("AI 来源")
                    Segmented(listOf("demo" to "演示模式", "remote" to "我的后端", "api" to "直接连接"), mode, "AI 来源", enabled = !busy) { id ->
                        l.editConfig { it.copy(aiMode = id) }
                    }
                    FinePrint(
                        when (mode) {
                            "demo" -> "演示模式用本地模板走完整个流程，不需要网络和密钥。"
                            "remote" -> "连接你自己部署在 Cloudflare 上的思辨场后端。AI 服务商的密钥存在后端里，这台设备只需要后端地址和访问口令。"
                            else -> "这台手机直接调用 AI 服务商的 Chat Completions 接口，不需要部署后端。API Key 加密保存在这台设备上。"
                        },
                    )
                }
                if (mode == "remote") RemotePanel(st, model)
                if (mode == "remote" && st.config.remote.url.isNotEmpty()) VoicePanel(st, model)
                if (mode == "api") DirectPanel(st, model)
                ReminderPanel(model)
                Panel {
                    SectionLabel("关于格式")
                    Text(
                        "讨论准备和理解复盘要求模型返回 JSON。较小的模型偶尔不按格式回答；遇到“没有按要求返回结构化结果”时，重试一次或换一个更强的模型。失败的请求不会改变讨论进度，也不会自动换成模板。",
                        style = TextStyles.small,
                        color = t.muted.color(),
                    )
                }
                Spacer(Modifier.height(bottom))
            }
        }
    }
}

// 复习提醒：这台设备自己的设置，默认关闭；打开时才请求通知权限（Android 13 起）
@Composable
private fun ReminderPanel(model: AppModel) {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(Reminders.prefs(context)) }
    fun apply(p: ReminderPrefs) {
        prefs = p
        Reminders.save(context, p)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            apply(prefs.copy(enabled = true))
        } else {
            model.learning.toast("没有通知权限，提醒发不出来。可以在系统设置里给思辨场打开通知。")
        }
    }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("复习提醒") }
            StatusTag(if (prefs.enabled) "已打开" else "已关闭", if (prefs.enabled) "ok" else "")
        }
        Segmented(listOf("off" to "不提醒", "on" to "每天提醒"), if (prefs.enabled) "on" else "off", "复习提醒") { id ->
            when {
                id == "off" -> apply(prefs.copy(enabled = false))
                Reminders.canNotify(context) -> apply(prefs.copy(enabled = true))
                else -> ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (prefs.enabled) {
            Row(Modifier.fillMaxWidth().scrollRow(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((h, m, label) in REMINDER_TIMES) Chip(label, prefs.hour == h && prefs.minute == m, { apply(prefs.copy(hour = h, minute = m)) })
            }
        }
        FinePrint("每天到点看一眼知识库：有到期的卡片才发一条通知，写着要复习哪几个概念；没有就不打扰。通知里没有讨论内容。")
    }
}

// 讯飞语音：密钥设在后端（Worker 的加密变量），这里只看配没配好、选发音人。和网页版 Settings 的 VoicePanel 一致
@Composable
private fun VoicePanel(st: AppState, model: AppModel) {
    val ready = st.ui.remote.tts
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("讯飞语音（可选）") }
            StatusTag(if (ready) "已配置" else "未配置", if (ready) "ok" else "warn")
        }
        Input(
            st.config.voiceName,
            { v -> model.learning.editConfig { it.copy(voiceName = v.take(64)) } },
            "发音人 ID",
            placeholder = "x4_xiaoyan",
            maxLength = 64,
        )
        FinePrint(
            "讯飞密钥设置在你的后端（Worker 的加密变量），这里不需要填写。配好后，发言旁会出现“朗读”，输入框旁会出现麦克风。" +
                "讯飞应用里要分别开通“在线语音合成”和“语音听写（流式版）”；发音人 ID 要用已开通的音色。只在点朗读或说话时调用讯飞，录音不保存。",
        )
    }
}

@Composable
private fun RemotePanel(st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf(st.config.remote.url) }
    // 口令只在输入框里停一下：连接后清空，已保存的不回显
    var password by rememberSaveable { mutableStateOf("") }
    val busy = st.ui.busy != null
    val saved = st.config.remote.url.isNotEmpty()
    val status = st.ui.remote
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("连接我的后端") }
            StatusTag(
                if (status.verified) {
                    "已连接，测试通过"
                } else if (saved) {
                    "已保存，未验证"
                } else {
                    "尚未连接"
                },
                if (status.verified) {
                    "ok"
                } else if (saved) {
                    "warn"
                } else {
                    ""
                },
            )
        }
        if (status.model.isNotEmpty()) {
            Text("后端使用的模型：${status.model}", style = TextStyles.small.copy(fontWeight = FontWeight.Medium), color = t.ink.color())
        }
        SectionLabel("后端地址")
        Input(
            url,
            {
                url = it
            },
            "后端地址",
            placeholder = "https://debating-ground-api.你的子域.workers.dev",
            keyboard = KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
            ),
        )
        SectionLabel("访问口令")
        Input(password, { password = it }, "访问口令", placeholder = if (saved) "已保存，不改就留空" else "部署时设置的 ACCESS_PASSWORD", secret = true)
        Text(
            "“连接并测试”会让后端调用一次模型，可能产生少量费用。后端地址和访问口令只保存在这台设备上，不会写进讨论记录、知识库或导出文件。",
            style = TextStyles.small,
            color = t.muted.color(),
        )
        QuietLink("还没有后端？看看怎么自己部署（免费额度就够用）", {
            context.startActivity(Intent(Intent.ACTION_VIEW, DOCS.toUri()))
        })
        PrimaryButton("连接并测试", {
            model.run {
                if (connectRemote(url, password)) password = ""
            }
        }, Modifier.fillMaxWidth(), enabled = !busy)
        if (saved) {
            GhostButton("从这台设备上删除", {
                l.forgetRemote()
                url = ""
                password = ""
            }, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}

@Composable
private fun DirectPanel(st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val status = st.ui.direct
    var url by rememberSaveable { mutableStateOf(status.baseURL) }
    var name by rememberSaveable { mutableStateOf(status.model) }
    // API Key 只在输入框里停一下：连接后清空，已保存的不回显、不进状态
    var key by remember { mutableStateOf("") }
    val busy = st.ui.busy != null
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("连接 AI 服务商") }
            StatusTag(
                if (status.verified) {
                    "已连接，测试通过"
                } else if (status.saved) {
                    "已保存，未验证"
                } else {
                    "尚未连接"
                },
                if (status.verified) {
                    "ok"
                } else if (status.saved) {
                    "warn"
                } else {
                    ""
                },
            )
        }
        SectionLabel("接口地址")
        Input(url, {
            url = it
        }, "接口地址", placeholder = "https://你的服务商地址/v1", keyboard = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
        FinePrint("Base URL 或完整的 Chat Completions 地址，会自动补上 /chat/completions。")
        SectionLabel("模型名称")
        Input(name, { name = it }, "模型名称", placeholder = "服务商支持的准确模型 ID")
        SectionLabel("API Key")
        Input(key, { key = it }, "API Key", placeholder = if (status.saved) "已保存，不改就留空" else "服务商控制台里的 API Key", secret = true)
        Text(
            "“连接并测试”会把密钥发送到你填写的接口，发起一次少量计费的测试。密钥用系统密钥库加密，只保存在这台设备上，不进系统备份，也不写进讨论记录、知识库或导出文件。",
            style = TextStyles.small,
            color = t.muted.color(),
        )
        PrimaryButton("连接并测试", {
            val typed = key
            key = ""
            model.run { connectDirect(url, name, typed) }
        }, Modifier.fillMaxWidth(), enabled = !busy)
        if (status.saved) {
            GhostButton("从这台设备上删除", {
                l.forgetDirect()
                url = ""
                name = ""
                key = ""
            }, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}
