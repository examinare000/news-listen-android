package com.rioikeda.newslisten.podcast.infra

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rioikeda.newslisten.podcast.app.PositionStore
import com.rioikeda.newslisten.podcast.domain.PositionRecord
import com.rioikeda.newslisten.podcast.domain.PositionRules
import com.rioikeda.newslisten.podcast.domain.ServerPosition
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * [PositionStore] の DataStore 実装。key は `pos/{subjectId}/{episodeId}`、値は JSON（`s` = 秒、`t` = epoch ミリ秒、`u` = 未送信）。
 * 主体の id は `/` を含まないので、接頭辞 `pos/{subjectId}/` で主体の記録を選べる。
 * 読めない値（JSON の破損・`s`/`t` の欠落・不正な値）は「無い」として扱い、[reclaim] が消す（android-dec-121 (7)）。
 */
class DataStorePositionStore(private val dataStore: DataStore<Preferences>) : PositionStore {

    override suspend fun read(subjectId: String, episodeId: String): PositionRecord? =
        decode(subjectId, episodeId, dataStore.data.first()[keyOf(subjectId, episodeId)])

    override suspend fun unsent(subjectId: String): List<PositionRecord> {
        val prefix = prefixOf(subjectId)
        return dataStore.data.first().asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(prefix)) return@mapNotNull null
            decode(subjectId, key.name.removePrefix(prefix), value as? String)
        }.filter { it.unsent }
    }

    override suspend fun write(record: PositionRecord) {
        dataStore.edit { it[keyOf(record.subjectId, record.episodeId)] = encode(record) }
    }

    override suspend fun markSent(subjectId: String, episodeId: String, recordedAt: Instant): Boolean {
        var marked = false
        dataStore.edit { prefs ->
            val stored = decode(subjectId, episodeId, prefs[keyOf(subjectId, episodeId)])
            if (stored != null && stored.recordedAt == recordedAt) {
                prefs[keyOf(subjectId, episodeId)] = encode(stored.copy(unsent = false))
                marked = true
            }
        }
        return marked
    }

    override suspend fun adoptIfNewer(subjectId: String, episodeId: String, server: ServerPosition): Boolean {
        var adopted = false
        dataStore.edit { prefs ->
            val stored = decode(subjectId, episodeId, prefs[keyOf(subjectId, episodeId)])
            val serverAt = server.recordedAt
            if (serverAt != null && PositionRules.shouldAdopt(stored, server)) {
                prefs[keyOf(subjectId, episodeId)] = encode(PositionRecord(subjectId, episodeId, server.seconds, serverAt, unsent = false))
                adopted = true
            }
        }
        return adopted
    }

    override suspend fun removeSubject(subjectId: String) {
        val prefix = prefixOf(subjectId)
        dataStore.edit { prefs -> prefs.removeWhere { it.startsWith(prefix) } }
    }

    override suspend fun reclaim(currentSubjectId: String?) {
        val keep = currentSubjectId?.let(::prefixOf)
        dataStore.edit { prefs ->
            val readable = prefs.asMap().filter { (key, value) ->
                keep != null && key.name.startsWith(keep) && decode(currentSubjectId, key.name.removePrefix(keep), value as? String) != null
            }.keys.mapTo(HashSet()) { it.name }
            prefs.removeWhere { name -> name.startsWith(POSITION_PREFIX) && name !in readable }
        }
    }

    private fun MutablePreferences.removeWhere(predicate: (String) -> Boolean) {
        asMap().keys.filter { predicate(it.name) }.forEach { remove(it) }
    }

    private fun keyOf(subjectId: String, episodeId: String) = stringPreferencesKey("${prefixOf(subjectId)}$episodeId")

    private fun prefixOf(subjectId: String) = "$POSITION_PREFIX$subjectId/"

    private fun encode(record: PositionRecord): String = buildJsonObject {
        put("s", record.seconds)
        put("t", record.recordedAt.toEpochMilli())
        put("u", record.unsent)
    }.toString()

    private fun decode(subjectId: String, episodeId: String, raw: String?): PositionRecord? {
        if (raw == null) return null
        return try {
            val json: JsonObject = Json.parseToJsonElement(raw).jsonObject
            PositionRecord(
                subjectId = subjectId,
                episodeId = episodeId,
                seconds = json.getValue("s").jsonPrimitive.double,
                recordedAt = Instant.ofEpochMilli(json.getValue("t").jsonPrimitive.long),
                unsent = json.getValue("u").jsonPrimitive.boolean,
            )
        } catch (_: IllegalArgumentException) {
            // 壊れた JSON・欠けた項目・型違い・不正な値は、読めない値として「無い」に倒す（SerializationException も IllegalArgumentException）。
            null
        } catch (_: IllegalStateException) {
            // 型違いの primitive（`boolean` など）は IllegalStateException を投げる。
            null
        } catch (_: NoSuchElementException) {
            null
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    private companion object {
        const val POSITION_PREFIX = "pos/"
    }
}
