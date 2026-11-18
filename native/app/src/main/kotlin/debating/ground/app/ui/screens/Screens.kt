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
