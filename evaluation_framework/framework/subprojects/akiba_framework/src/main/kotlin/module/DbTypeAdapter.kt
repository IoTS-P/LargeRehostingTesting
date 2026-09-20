package org.iotsplab.akiba.module

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

interface DbTypeAdapter {
    fun set(ps: PreparedStatement, index: Int, value: Any?)
}

val defaultDbTypeAdapters: Map<String, DbTypeAdapter> = mapOf(
    "integer" to IntegerAdapter,
    "bigint" to BigIntAdapter,
    "text" to TextAdapter,
    "json" to JsonAdapter,
    "jsonb" to JsonbAdapter,
    "float" to FloatAdapter,
    "double precision" to DoubleAdapter,
    "bytea" to ByteaAdapter,
    "boolean" to BooleanAdapter,
    "timestamp" to TimestampAdapter,
    "timestamptz" to TimestampTzAdapter,
    "interval" to IntervalAdapter
)

object IntegerAdapter : DbTypeAdapter {
    override fun set(ps: PreparedStatement, index: Int, value: Any?) {
        if (value == null) ps.setNull(index, Types.INTEGER)
        else ps.setInt(index, value as Int)
    }
}

object BigIntAdapter : DbTypeAdapter {
    override fun set(ps: PreparedStatement, index: Int, value: Any?) {
        if (value == null) ps.setNull(index, Types.BIGINT)
        else ps.setLong(index, value as Long)
    }
}

object TextAdapter : DbTypeAdapter {
    override fun set(ps: PreparedStatement, index: Int, value: Any?) {
        if (value == null) ps.setNull(index, Types.VARCHAR)
        else ps.setString(index, value as String)
    }
}

object JsonAdapter : DbTypeAdapter {

    private val mapper = jacksonObjectMapper()

    override fun set(ps: PreparedStatement, index: Int, value: Any?) {
        set(ps, index, value, "jsonb")
    }

    fun set(ps: PreparedStatement, index: Int, value: Any?, jsonType: String) {
        if (value == null) {
            ps.setNull(index, Types.OTHER)
            return
        }

        val jsonString = when (value) {
            is String -> value                // assume to be a JSON
            is Map<*, *> -> mapper.writeValueAsString(value)
            is List<*> -> mapper.writeValueAsString(value)
            else -> mapper.writeValueAsString(value)   // any object -> JSON
        }

        val obj = org.postgresql.util.PGobject().apply {
            type = jsonType
            this.value = jsonString
        }

        ps.setObject(index, obj)
    }
}

object JsonbAdapter : DbTypeAdapter {
    override fun set(ps: PreparedStatement, index: Int, value: Any?) {
        JsonAdapter.set(ps, index, value, "jsonb")
    }
}

object FloatAdapter : DbTypeAdapter {
    override fun set(ps: PreparedStatement, index: Int, value: Any?) {
        if (value == null) ps.setNull(index, Types.FLOAT)
        else ps.setFloat(index, value as Float)
    }
}

object DoubleAdapter : DbTypeAdapter {
    override fun set(ps: PreparedStatement, index: Int, value: Any?) {
        if (value == null) ps.setNull(index, Types.DOUBLE)
        else ps.setDouble(index, value as Double)
    }
}

object ByteaAdapter : DbTypeAdapter {
    override fun set(ps: PreparedStatement, index: Int, value: Any?) {
        if (value == null) ps.setNull(index, Types.BINARY)
        else ps.setBytes(index, value as ByteArray)
    }
}

object BooleanAdapter : DbTypeAdapter {
    override fun set(ps: PreparedStatement, index: Int, value: Any?) {
        if (value == null) ps.setNull(index, Types.BOOLEAN)
        else ps.setBoolean(index, value as Boolean)
    }
}

object TimestampAdapter : DbTypeAdapter {
    override fun set(ps: PreparedStatement, index: Int, value: Any?) {
        if (value == null) ps.setNull(index, Types.TIMESTAMP)
        else ps.setTimestamp(index, Timestamp.valueOf(value as LocalDateTime))
    }
}

object TimestampTzAdapter : DbTypeAdapter {
    override fun set(ps: PreparedStatement, index: Int, value: Any?) {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
            return
        }

        val ldt = value as LocalDateTime

        // LocalDateTime + offset -> OffsetDateTime
        val odt = ldt.atOffset(ZoneOffset.UTC)

        ps.setObject(index, odt)
    }
}

object IntervalAdapter : DbTypeAdapter {

    override fun set(ps: PreparedStatement, index: Int, value: Any?) {
        if (value == null) {
            ps.setNull(index, Types.OTHER)
            return
        }

        val pgInterval = when (value) {
            is String -> {
                // allow to send PostgreSQL interval string
                org.postgresql.util.PGInterval(value)
            }
            is Duration -> {
                // parse Duration to PGInterval（d, s, ms）
                val seconds = value.seconds
                val microseconds = value.nano / 1000
                org.postgresql.util.PGInterval(
                    /* years = */ 0,
                    /* months = */ 0,
                    /* days = */ (seconds / 86400).toInt(),
                    /* hours = */ ((seconds % 86400) / 3600).toInt(),
                    /* minutes = */ ((seconds % 3600) / 60).toInt(),
                    /* seconds = */ ((seconds % 60) + microseconds / 1_000_000.0)
                )
            }
            else -> {
                throw IllegalArgumentException(
                    "Unsupported type for INTERVAL: ${value::class}"
                )
            }
        }

        ps.setObject(index, pgInterval)
    }
}

