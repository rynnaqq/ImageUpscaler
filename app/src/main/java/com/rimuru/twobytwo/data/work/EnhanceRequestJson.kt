package com.rimuru.twobytwo.data.work

import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.DenoiseStrength
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.EngineMode
import org.json.JSONArray
import org.json.JSONObject

internal object EnhanceRequestJson {
    fun encode(request: EnhanceRequest): String = JSONObject().apply {
        put("inputUris", JSONArray(request.inputUris))
        put("scale", request.scale.multiplier)
        put("mode", request.mode.name)
        put("denoise", request.denoise.percent)
        put("faceRestore", request.faceRestoreEnabled)
        put("faceStrength", request.faceRestoreStrength)
        put("accelerator", request.accelerator.name)
        put("neural", request.useNeuralEngine)
        put("sharpen", request.sharpen)
        put("deblurEnabled", request.deblurEnabled)
        put("deblurStrength", request.deblurStrength)
        put("scratchRepairEnabled", request.scratchRepairEnabled)
        put("scratchRepairStrength", request.scratchRepairStrength)
        put("colorizeEnabled", request.colorizeEnabled)
        put("colorizeStrength", request.colorizeStrength)
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
            )
        }.getOrNull()
    }
}
