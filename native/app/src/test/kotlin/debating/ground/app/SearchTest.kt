package debating.ground.app

import debating.ground.app.data.Note
import debating.ground.app.data.SibianJson
import debating.ground.app.data.normalizeForSearch
import debating.ground.app.data.searchCards
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// 知识库搜索：同样的笔记和查询，结果和顺序都要和网页版 src/lib/search.ts 一样（search-cases.json）
class SearchTest {
    @Test
    fun searchMatchesTheWeb() {
        val o = SibianJson.parseToJsonElement(File(javaClass.classLoader!!.getResource("fixtures/search-cases.json")!!.toURI()).readText()).jsonObject
        val cards = SibianJson.decodeFromJsonElement(ListSerializer(Note.serializer()), o.getValue("cards"))
        for (c in o.getValue("queries") as JsonArray) {
            val q = c.jsonObject
            val query = q.getValue("q").jsonPrimitive.content
            assertEquals("「$query」规整后", q.getValue("norm").jsonPrimitive.content, normalizeForSearch(query))
            assertEquals("「$query」的结果", q.getValue("ids").jsonArray.map { it.jsonPrimitive.content }, searchCards(cards, query).map { it.id })
        }
    }
}
