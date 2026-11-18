package debating.ground.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.Sans
import debating.ground.app.ui.theme.Serif
import debating.ground.app.ui.theme.TextSize
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color

// M0 只有页面骨架：各页的真实内容在后续阶段（#70）加入。
// 这里的说明文字是给试装开发版的人看的，说清楚现在能做什么、什么还没有。

@Composable
fun LearnScreen() = Page {
    val t = LocalTokens.current
    Brand()
    Text("今天想弄懂\n什么？", style = TextStyles.display, color = t.ink.color(), modifier = Modifier.semantics { heading() })
    Note("这是原生版的开发版本。写下概念、和 AI 讨论的功能会在后面几个版本里陆续加进来；现在请继续用正式版学习。")
}

@Composable
fun LibraryScreen() = Page {
    Heading("知识库")
    Note("讨论结束后的复盘会存成知识卡片，按间隔提醒你复习。")
}

@Composable
fun SettingsScreen() = Page {
    Heading("设置")
    Note("AI 的连接方式（演示模式、自己部署的后端、直接连接服务商）会在这里设置。")
}

// 品牌标志：深绿圆角方块里一个衬线“〞”，和网页版 .brand、.brand-mark 一样
@Composable
fun Brand(modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp, bottomEnd = 9.dp, bottomStart = 3.dp)).background(t.brand.color()),
            contentAlignment = Alignment.Center,
        ) { Text("〞", color = t.brandInk.color(), style = TextStyles.body.copy(fontFamily = Serif, fontSize = 20.sp, lineHeight = 20.sp)) }
        Text("思辨场", color = t.ink.color(), style = TextStyles.body.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.12.em))
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun Heading(text: String) = Text(
    text,
    style = TextStyles.title.copy(fontFamily = Sans, fontSize = TextSize.xl),
    color = LocalTokens.current.ink.color(),
    modifier = Modifier.semantics {
        heading()
    },
)

@Composable
private fun Note(text: String) = Text(text, style = TextStyles.small, color = LocalTokens.current.muted.color())
