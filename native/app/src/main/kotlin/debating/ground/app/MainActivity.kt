package debating.ground.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import debating.ground.app.migrate.LegacyImport
import debating.ground.app.migrate.WebViewLegacySource
import debating.ground.app.room.inviteIn
import debating.ground.app.ui.AppScaffold
import debating.ground.app.ui.Atmosphere
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.theme.SibianTheme

class MainActivity : ComponentActivity() {
    // 从分享打开时带来的意图，等存档读好再处理
    private var pending by mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pending = intent
    }

    private fun open(intent: Intent, m: AppModel) {
        when (intent.action) {
            // 分享来的文字：里面有邀请链接就进加入页（链接不完整时 openJoin 会说明）
            Intent.ACTION_SEND -> {
                val link = inviteIn(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
                if (link != null) m.learning.rooms.openJoin(link) else m.learning.toast("这段文字里没有邀请链接。")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pending = intent
        // 内容画到系统栏下面，由 Scaffold 和导航栏组件让出安全区域
        enableEdgeToEdge()
        // 系统设置里把动画关掉（开发者选项的“动画时长缩放”为 0，或无障碍里的“移除动画”）时，背景不漂
        val motion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        setContent {
            CompositionLocalProvider(LocalMotion provides motion) {
                SibianTheme {
                    var model by remember { mutableStateOf(AppModel.loaded()) }
                    // 从套壳版升级上来的第一次启动：先把旧 App 的知识库搬过来，再读存档（一般不到一秒）
                    LaunchedEffect(Unit) {
                        if (model != null) return@LaunchedEffect
                        val import = LegacyImport(filesDir, WebViewLegacySource(this@MainActivity))
                        val result = if (import.pending()) runCatching { import.run() }.getOrNull() else null
                        val m = AppModel.get(this@MainActivity)
                        if (result != null && result.cards > 0) m.learning.toast("已从旧版搬来知识库，整理成了 ${result.cards} 篇概念笔记。")
                        model = m
                    }
                    // 从分享打开时要去的地方：读好存档以后再去
                    LaunchedEffect(model, pending) {
                        val m = model ?: return@LaunchedEffect
                        pending?.let { open(it, m) }
                        pending = null
                    }
                    model?.let { AppScaffold(it) } ?: Box(Modifier.fillMaxSize()) { Atmosphere() }
                }
            }
        }
    }
}
