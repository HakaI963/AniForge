package dev.aniforge.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent / app intent parser: human sentence(s) → Studio API ops.
 * (The OpenCode agent plans full edits; this parser covers quick one-liner intents.)
 */
object IntentParser {

    class IntentResult {
        val ops = mutableListOf<JsonObject>()
        var renderPreset: String? = null
    }

    private val num = Regex("-?[0-9]+(?:\\.[0-9]+)?")

    fun parse(prompt: String): IntentResult {
        val out = IntentResult()
        val text = prompt.trim().lowercase()

        // cut at 4.5 [on c2]
        Regex("cut at ([0-9.]+)(?:\\s+on\\s+([\\w-]+))?").find(text)?.let { m ->
            out.ops += buildJsonObject {
                put("op", "cut")
                put("clip_id", m.groupValues[2].ifBlank { "c1" })
                put("at", m.groupValues[1].toDouble())
            }
        }

        // speed 2x / speed 0.3 [on c2]
        Regex("speed ([0-9.]+)x?(?:\\s+on\\s+([\\w-]+))?").find(text)?.let { m ->
            out.ops += buildJsonObject {
                put("op", "speed")
                put("clip_id", m.groupValues[2].ifBlank { "c1" })
                put("speed", m.groupValues[1].toDouble())
            }
        }

        // chroma c2 (default key green)
        Regex("chroma\\s*([\\w-]*)(?:\\s+with\\s+(#[0-9a-fA-F]{6}))?").find(text)?.let { m ->
            out.ops += buildJsonObject {
                put("op", "add_effect")
                put("clip_id", m.groupValues[1].ifBlank { "c1" })
                put("effect", buildJsonObject {
                    put("type", "ChromaKey")
                    put("params", buildJsonObject {
                        put("background", m.groupValues[2].ifBlank { "#00FF00" })
                        put("tolerance", 0.18)
                        put("softness", 0.05)
                    })
                })
            }
        }

        // text 第3話 (first content-bearing glossary clip)
        Regex("text\\s+(.+)").find(prompt)?.let { m ->
            out.ops += buildJsonObject {
                put("op", "set_text")
                put("clip_id", "c1")
                put("text", buildJsonObject {
                    put("content", m.groupValues[1].trim())
                    put("style", "anime")
                    put("position", "center")
                })
            }
        }

        // crop 4:5 / 1:1 / 9:16 / 16:9 / 21:9
        Regex("crop ([0-9]+):([0-9]+)").find(text)?.let { m ->
            val ratio = "${m.groupValues[1]}:${m.groupValues[2]}"
            out.ops += buildJsonObject {
                put("op", "crop")
                put("clip_id", "c1")
                put("ratio", ratio)
            }
        }

        // flash at 8
        Regex("flash at ([0-9.]+)").find(text)?.let { m ->
            out.ops += buildJsonObject {
                put("op", "flash_frame")
                put("at", m.groupValues[1].toDouble())
                put("duration", 0.05)
            }
        }

        // render shonen-flash etc
        Regex("render ([\\w-]+)").find(text)?.let { m ->
            out.renderPreset = m.groupValues[1]
        }

        return out
    }
}
