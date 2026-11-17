package debating.ground.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import debating.ground.app.ui.AppScaffold
import debating.ground.app.ui.theme.SibianTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容画到系统栏下面，由 Scaffold 和导航栏组件让出安全区域
        enableEdgeToEdge()
        setContent { SibianTheme { AppScaffold() } }
    }
}
