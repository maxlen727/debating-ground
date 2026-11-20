package debating.ground.app

import debating.ground.app.data.SibianJson
import debating.ground.app.room.encodeQr
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

// 邀请二维码（#151）：和网页版 src/lib/qr.ts 生成的标准答案逐格比对，挑的版本和掩码也要一样
class QrTest {
    private fun rows(m: Array<BooleanArray>) = m.map { r -> r.joinToString("") { if (it) "1" else "0" } }

    @Test
    fun matchesTheWebEncoderCellForCell() {
        val cases = SibianJson.parseToJsonElement(File(javaClass.classLoader!!.getResource("fixtures/qr-cases.json")!!.toURI()).readText()).jsonArray
        assertEquals(5, cases.size)
        for (c in cases) {
            val o = c.jsonObject
            val text = o.getValue("text").jsonPrimitive.content
            val q = encodeQr(text)!!
            assertEquals(text, o.getValue("version").jsonPrimitive.int, q.version)
            assertEquals(text, o.getValue("mask").jsonPrimitive.int, q.mask)
            assertEquals(text, o.getValue("rows").jsonArray.map { it.jsonPrimitive.content }, rows(q.modules))
            assertEquals(text, o.getValue("mask6").jsonArray.map { it.jsonPrimitive.content }, rows(encodeQr(text, 6)!!.modules))
        }
    }

    @Test
    fun tooLongDoesNotFit() {
        assertEquals(40, encodeQr("x".repeat(2331))!!.version)
        assertNull(encodeQr("x".repeat(2332)))
    }
}
