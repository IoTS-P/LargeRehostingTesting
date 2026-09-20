package org.iotsplab.akiba.client.database

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import org.postgresql.util.PGInterval
import java.time.Duration
import java.util.Base64
import kotlin.math.roundToLong

object ServerModDataDeserializer: JsonDeserializer<Map<String, Any?>>() {
    @Throws(IllegalArgumentException::class)
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext
    ): Map<String, Any?>? {
        val node = parser.codec.readTree<JsonNode>( parser)
        val data = mutableMapOf<String, Any?>()

        node["results"].let {
            require(it.isArray) { "Module data has bad format: /results must be an array" }
            it.forEach { entry ->
                val name = entry["name"].textValue()
                if (entry["value"].isNull) {
                    data[name] = null
                    return@forEach
                }
                data[name] = when (entry["type"].textValue().lowercase()) {
                    in listOf("text", "json", "jsonb") -> entry["value"].textValue()
                    in listOf("int4", "integer") -> entry["value"].intValue()
                    in listOf("int8", "bigint") -> entry["value"].longValue()
                    "double precision" -> entry["value"].doubleValue()
                    in listOf("bool", "boolean") -> entry["value"].booleanValue()
                    "bytea" -> Base64.getDecoder().decode(entry["value"].textValue())
                    "interval" -> Duration.ofMillis(
                        (PGInterval(entry["value"].textValue()).seconds * 1000).roundToLong())
                    else -> throw IllegalArgumentException(
                        "Module data has bad format: /results/$name has unsupported type $it")
                }
            }
        }

        return data
    }
}