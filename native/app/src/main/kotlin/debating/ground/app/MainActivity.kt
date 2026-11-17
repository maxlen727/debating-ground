package debating.ground.app

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import debating.ground.app.ui.AppScaffold
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.theme.SibianTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容画到系统栏下面，由 Scaffold 和导航栏组件让出安全区域
        enableEdgeToEdge()
        // 系统设置里把动画关掉（开发者选项的“动画时长缩放”为 0，或无障碍里的“移除动画”）时，背景不漂
        val motion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        setContent { CompositionLocalProvider(LocalMotion provides motion) { SibianTheme { AppScaffold() } } }
    }
}
