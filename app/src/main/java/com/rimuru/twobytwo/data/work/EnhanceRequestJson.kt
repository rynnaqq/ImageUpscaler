package com.rimuru.twobytwo.data.work

import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.CropPreset
import com.rimuru.twobytwo.domain.model.DenoiseStrength
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.EngineMode
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.model.OutputFormat
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

internal object EnhanceRequestJson {
    fun encode(request: EnhanceRequest): String = JSONObject().apply {
        put("inputUris", JSONArray(request.inputUris))
        putSettings(this, request)
    }.toString()

    fun encodeSettings(request: EnhanceRequest): String = JSONObject().apply {
        putSettings(this, request)
    }.toString()

    fun decode(json: String?): EnhanceRequest? {
        if (json == null) return null
        return runCatching {
            val o = JSONObject(json)
            val uris = mutableListOf<String>()
            val arr = o.getJSONArray("inputUris")
            for (i in 0 until arr.length()) uris += arr.getString(i)
            EnhanceRequest(
                inputUris = uris,
                scale = parseScaleFactor(o.getInt("scale")),
                mode = if (o.getString("mode") == "PRECISION") EngineMode.PRECISION else EngineMode.CREATIVE,
                denoise = DenoiseStrength(o.getInt("denoise")),
                faceRestoreEnabled = o.getBoolean("faceRestore"),
                faceRestoreStrength = o.getInt("faceStrength"),
                accelerator = Accelerator.entries.first { it.name == o.optString("accelerator", "AUTO") },
                useNeuralEngine = o.optBoolean("neural", true),
                sharpen = o.optBoolean("sharpen", true),
                deblurEnabled = o.optBoolean("deblurEnabled", false),
                deblurStrength = o.optInt("deblurStrength", 50),
                scratchRepairEnabled = o.optBoolean("scratchRepairEnabled", false),
                scratchRepairStrength = o.optInt("scratchRepairStrength", 50),
                colorizeEnabled = o.optBoolean("colorizeEnabled", false),
                colorizeStrength = o.optInt("colorizeStrength", 50),
                cropPreset = parseCropPreset(o),
                cacheLimitBytes = parseCacheLimit(o),
                exportPolicy = parseExportPolicy(o),
            )
        }.getOrNull()
    }

    private fun putSettings(json: JSONObject, request: EnhanceRequest) {
        json.put("scale", request.scale.multiplier)
        json.put("mode", request.mode.name)
        json.put("denoise", request.denoise.percent)
        json.put("faceRestore", request.faceRestoreEnabled)
        json.put("faceStrength", request.faceRestoreStrength)
        json.put("accelerator", request.accelerator.name)
        json.put("neural", request.useNeuralEngine)
        json.put("sharpen", request.sharpen)
        json.put("deblurEnabled", request.deblurEnabled)
        json.put("deblurStrength", request.deblurStrength)
        json.put("scratchRepairEnabled", request.scratchRepairEnabled)
        json.put("scratchRepairStrength", request.scratchRepairStrength)
        json.put("colorizeEnabled", request.colorizeEnabled)
        json.put("colorizeStrength", request.colorizeStrength)
        json.put("cropPreset", request.cropPreset?.name ?: JSONObject.NULL)
        json.put("cacheLimitBytes", request.cacheLimitBytes)
        json.put("exportFormat", request.exportPolicy.format.name)
        json.put("jpegQuality", request.exportPolicy.jpegQuality)
        json.put("keepExif", request.exportPolicy.keepExif)
        json.put("keepGps", request.exportPolicy.keepGps)
    }

    private fun parseCropPreset(json: JSONObject): CropPreset? {
        if (!json.has("cropPreset") || json.isNull("cropPreset")) return null
        val name = json.opt("cropPreset") as? String ?: throw IllegalArgumentException("crop preset must be a string")
        return CropPreset.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("unknown crop preset: $name")
    }

    private fun parseCacheLimit(json: JSONObject): Long {
        if (!json.has("cacheLimitBytes")) {
            return EnhanceRequest.DEFAULT_CACHE_LIMIT_BYTES
        }
        val value = json.opt("cacheLimitBytes") as? Number
            ?: throw IllegalArgumentException("cache limit must be a number")
        val limit = when (value) {
            is Byte, is Short, is Int, is Long -> value.toLong()
            is BigInteger -> value.longValueExact()
            is BigDecimal -> {
                if (value.scale() > 0) throw IllegalArgumentException("cache limit must be integral")
                value.toBigIntegerExact().longValueExact()
            }
            else -> throw IllegalArgumentException("cache limit must be integral")
        }
        require(limit in EnhanceRequest.MIN_CACHE_LIMIT_BYTES..EnhanceRequest.MAX_CACHE_LIMIT_BYTES) {
            "cache limit out of range: $limit"
        }
        return limit
    }

    private fun parseExportPolicy(json: JSONObject): ExportPolicy = ExportPolicy(
        format = parseOutputFormat(json),
        jpegQuality = parseJpegQuality(json),
        keepExif = parseBoolean(json, "keepExif", true),
        keepGps = parseBoolean(json, "keepGps", false),
    )

    private fun parseOutputFormat(json: JSONObject): OutputFormat {
        if (!json.has("exportFormat") || json.isNull("exportFormat")) return OutputFormat.PNG
        val name = json.opt("exportFormat") as? String
            ?: throw IllegalArgumentException("export format must be a string")
        return OutputFormat.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("unknown export format: $name")
    }

    private fun parseJpegQuality(json: JSONObject): Int {
        if (!json.has("jpegQuality") || json.isNull("jpegQuality")) return 97
        val value = json.opt("jpegQuality") as? Number
            ?: throw IllegalArgumentException("JPEG quality must be a number")
        val integer = when (value) {
            is BigInteger -> value
            is BigDecimal -> value.toBigIntegerExact()
            else -> BigDecimal(value.toString()).toBigIntegerExact()
        }
        return integer.coerceIn(
            BigInteger.valueOf(Int.MIN_VALUE.toLong()),
            BigInteger.valueOf(Int.MAX_VALUE.toLong()),
        ).toInt()
    }

    private fun parseBoolean(json: JSONObject, key: String, default: Boolean): Boolean {
        if (!json.has(key) || json.isNull(key)) return default
        return json.opt(key) as? Boolean
            ?: throw IllegalArgumentException("$key must be a boolean")
    }
}
