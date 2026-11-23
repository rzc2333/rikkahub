package me.rerere.rikkahub.data.sync.importer

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class StJsonlImporterTest {

    @Test
    fun `parses header messages swipes and hidden floors`() {
        val jsonl = """
            {"user_name":"User","character_name":"Alice","chat_metadata":{}}
            {"name":"Alice","is_user":false,"mes":"hello","send_date":"2026-11-15T10:51:06.760Z"}
            {"name":"User","is_user":true,"mes":"hi","send_date":1760000000}
            {"name":"Alice","is_user":false,"mes":"branch","swipes":["branch","alt"],"swipe_id":1,"send_date":"2026-11-15T10:53:54.796Z"}
            {"name":"Alice","is_user":false,"is_system":true,"mes":"hidden note"}
            {"name":"Alice","is_user":false,"mes":"*narrator line*","extra":{"type":"narrator"}}
        """.trimIndent()
        val result = StJsonlImporter.parse(jsonl, Uuid.random(), fallbackTitle = "backup file")
        val conversation = result.conversation
        assertEquals("Alice", conversation.title)
        assertEquals(5, result.importedMessages)
        assertEquals(1, result.skippedHiddenMessages)
        assertEquals(5, conversation.messageNodes.size)
        // swipes → branches
        val branchNode = conversation.messageNodes[2]
        assertEquals(2, branchNode.messages.size)
        assertEquals(1, branchNode.selectIndex)
        // hidden floor kept as normal message (upstream has no hide mechanism)
        assertEquals(MessageRole.ASSISTANT, conversation.messageNodes[3].currentMessage.role)
        // narrator kept as normal message
        assertEquals(MessageRole.ASSISTANT, conversation.messageNodes[4].currentMessage.role)
        // timestamps
        assertEquals(Instant.parse("2026-11-15T10:51:06.760Z"), conversation.createAt)
    }

    @Test
    fun `title falls back to file name then character name then default`() {
        val jsonl = """
            {"user_name":"unused","character_name":"unused","chat_metadata":{}}
            {"name":"Alice","is_user":false,"mes":"hello"}
        """.trimIndent()
        val withFileName = StJsonlImporter.parse(jsonl, Uuid.random(), fallbackTitle = "my_backup")
        assertEquals("my_backup", withFileName.conversation.title)
        val withCharacter = StJsonlImporter.parse(jsonl, Uuid.random(), fallbackTitle = "")
        assertEquals("Alice", withCharacter.conversation.title)
        val withDefault = StJsonlImporter.parse("{\"user_name\":\"unused\"}", Uuid.random())
        assertEquals("Imported ST Chat", withDefault.conversation.title)
    }

    @Test
    fun `legacy send date and epoch seconds are parsed`() {
        val jsonl = """
            {"user_name":"User","character_name":"Alice"}
            {"name":"Alice","is_user":false,"mes":"a","send_date":"2024-1-5 @14h 30m 25s 500ms"}
            {"name":"User","is_user":true,"mes":"b","send_date":1700000000}
        """.trimIndent()
        val result = StJsonlImporter.parse(jsonl, Uuid.random())
        val times = result.conversation.messageNodes.map { it.currentMessage.createdAt }
        // legacy format parsed in local timezone; epoch seconds normalized to millis
        assertTrue(times[0] < times[1])
    }

    @Test
    fun `non message lines are ignored`() {
        val jsonl = """
            {"chat_metadata":{}}
            {"foo":"bar"}
            {"mes":"ok","is_user":true}
            not-json-at-all
        """.trimIndent()
        val result = StJsonlImporter.parse(jsonl, Uuid.random())
        assertEquals(1, result.importedMessages)
        assertEquals("ok", result.conversation.messageNodes.single().currentMessage.toText())
        assertEquals("Imported ST Chat", result.conversation.title)
    }
}
