package me.rerere.rikkahub.data.sync.importer

import kotlin.time.Instant as KotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/**
 * SillyTavern `.jsonl` 聊天记录导入。
 *
 * 文件结构：首行是会话头（`chat_metadata` / `user_name` / `character_name`），
 * 之后每行一条消息（`mes` 正文、`is_user`、`is_system`、`send_date`、`swipes`/`swipe_id` 分支）。
 *
 * 兼容行为：
 * - `is_system`（酒馆 /hide 隐藏楼层）在上游没有隐藏机制，跳过并计数，避免混入正文；
 * - `extra.type = narrator` 旁白在上游没有对应概念，作为普通消息保留，保证内容不丢；
 * - `swipes` 多分支映射为 [MessageNode] 的多分支，`swipe_id` 为选中下标；
 * - `send_date` 支持 epoch 秒/毫秒、ISO 时间与酒馆遗留格式，解析失败时顺延上一条的时间；
 * - 图片只存在于酒馆的附件体系中，jsonl 里没有可恢复的图片数据，直接忽略。
 */
object StJsonlImporter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private const val MAX_MESSAGES = 100_000

    // 酒馆遗留时间格式：2024-1-5 @14h 30m 25s 500ms
    private val LEGACY_SEND_DATE = Regex(
        """^(\d{4})-(\d{1,2})-(\d{1,2})\s*@?(\d{1,2})h\s+(\d{1,2})m\s+(\d{1,2})s\s+(\d{1,3})ms$"""
    )

    data class Result(
        val conversation: Conversation,
        val importedMessages: Int,
        val skippedHiddenMessages: Int,
    )

    fun parse(
        jsonl: String,
        assistantId: Uuid,
        fallbackTitle: String = "",
        now: Instant = Instant.now(),
    ): Result {
        val lines = jsonl.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.isNotEmpty()) { "Chat archive is empty" }

        var title: String? = null
        var characterName: String? = null
        val nodes = mutableListOf<MessageNode>()
        var imported = 0
        var skippedHidden = 0
        var previousTime = Long.MIN_VALUE
        var firstTime: Instant? = null
        var lastTime: Instant? = null

        for ((index, line) in lines.withIndex()) {
            val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (index == 0 && !obj.containsKey("mes") &&
                (obj.containsKey("chat_metadata") || obj.containsKey("user_name") || obj.containsKey("character_name"))
            ) {
                characterName = obj["character_name"]?.primitiveOrNull?.contentOrNull
                    ?.takeIf { it.isNotBlank() && !it.equals("unused", ignoreCase = true) }
                title = obj["chat_metadata"]?.jsonObjectOrNull
                    ?.get("rpclient")?.jsonObjectOrNull
                    ?.get("title")?.primitiveOrNull?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                continue
            }
            val mes = obj["mes"]?.primitiveOrNull?.contentOrNull ?: continue
            require(nodes.size < MAX_MESSAGES) { "Chat archive has too many messages" }

            val isUser = obj["is_user"]?.primitiveOrNull?.booleanOrNull == true
            val isHidden = obj["is_system"]?.primitiveOrNull?.booleanOrNull == true
            if (isHidden) skippedHidden++

            val swipes = obj["swipes"]?.arrayOrNull
                ?.mapNotNull { it.primitiveOrNull?.contentOrNull }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val texts = swipes.ifEmpty { listOf(mes) }
            val swipeId = (obj["swipe_id"]?.primitiveOrNull?.intOrNull ?: 0)
                .coerceIn(0, texts.lastIndex.coerceAtLeast(0))

            val time = parseSendDate(obj["send_date"])?.let { parsed ->
                if (parsed > previousTime) parsed else previousTime + 1
            } ?: if (previousTime == Long.MIN_VALUE) now.toEpochMilli() else previousTime + 1
            previousTime = time
            if (firstTime == null) firstTime = Instant.ofEpochMilli(time)
            lastTime = Instant.ofEpochMilli(time)

            val message = UIMessage(
                id = Uuid.random(),
                role = if (isUser) MessageRole.USER else MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(texts[swipeId])),
                createdAt = millisToLocalDateTime(time),
            )
            val node = MessageNode(
                id = Uuid.random(),
                messages = texts.map { t ->
                    message.copy(parts = listOf(UIMessagePart.Text(t)))
                },
                selectIndex = swipeId,
            )
            nodes += node
            imported++
        }

        // 标题优先用导入文件名（fallbackTitle），其次才是元数据里的标题和角色名
        val resolvedTitle = fallbackTitle.trim().takeIf { it.isNotEmpty() }
            ?: title
            ?: characterName
            ?: "Imported ST Chat"
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = assistantId,
            title = resolvedTitle,
            messageNodes = nodes,
            createAt = firstTime ?: now,
            updateAt = lastTime ?: now,
        )
        return Result(
            conversation = conversation,
            importedMessages = imported,
            skippedHiddenMessages = skippedHidden,
        )
    }

    private fun parseSendDate(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) {
            return primitive.longOrNull?.let(::normalizeEpoch)
        }
        val text = primitive.content.trim()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return normalizeEpoch(it) }
        runCatching { return Instant.parse(text).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
        LEGACY_SEND_DATE.matchEntire(text)?.destructured?.let { (year, month, day, hour, minute, second, millis) ->
            runCatching {
                return LocalDateTime.of(
                    year.toInt(),
                    month.toInt(),
                    day.toInt(),
                    hour.toInt(),
                    minute.toInt(),
                    second.toInt(),
                    millis.toInt() * 1_000_000,
                )
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
        }
        return null
    }

    private fun normalizeEpoch(value: Long): Long =
        if (value in -100_000_000_000L..100_000_000_000L) value * 1000 else value

    private fun millisToLocalDateTime(timestamp: Long): kotlinx.datetime.LocalDateTime =
        KotlinInstant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault())

    private val JsonElement.primitiveOrNull: JsonPrimitive?
        get() = this as? JsonPrimitive

    private val JsonElement.arrayOrNull: JsonArray?
        get() = this as? JsonArray

    private val JsonElement.jsonObjectOrNull: JsonObject?
        get() = this as? JsonObject
}
