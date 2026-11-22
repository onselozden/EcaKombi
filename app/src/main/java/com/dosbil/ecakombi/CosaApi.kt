package com.dosbil.ecakombi

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * E.C.A. Smart Devices uygulamasının kullandığı COSA bulut API'si (kiwi.cosa.com.tr).
 * Uç noktalar orijinal APK 4.3.0'dan çıkarıldı.
 */
class CosaApi(var token: String? = null) {

    companion object {
        const val BASE = "https://kiwi.cosa.com.tr"
    }

    class ApiException(msg: String) : Exception(msg)

    private fun post(path: String, body: JSONObject = JSONObject()): JSONObject {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json;charset=utf-8")
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        token?.let { conn.setRequestProperty("authToken", it) }

        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        val json = try { JSONObject(text) } catch (e: Exception) {
            throw ApiException("HTTP $code: ${text.take(200)}")
        }
        if (code == 401 || code == 403) throw ApiException("Oturum süresi doldu, tekrar giriş yap")
        if (code !in 200..299 || json.optInt("ok", 1) == 0) {
            val msg = json.optString("message", json.optString("error", json.optString("code", "HTTP $code")))
            throw ApiException(msg)
        }
        return json
    }

    fun login(email: String, password: String): String {
        val r = post("/api/users/login", JSONObject().put("email", email).put("password", password))
        val t = r.optString("authToken", r.optString("token", ""))
        if (t.isEmpty()) throw ApiException("Giriş başarısız")
        token = t
        return t
    }

    data class Endpoint(val id: String, val name: String)

    fun getEndpoints(): List<Endpoint> {
        val arr = post("/api/endpoints/getEndpoints").optJSONArray("endpoints") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Endpoint(o.optString("id", o.optString("_id")), o.optString("name", "Termostat"))
        }
    }

    data class Status(
        val name: String,
        val temperature: Double?,
        val humidity: Double?,
        val targetTemperature: Double?,
        val combiOn: Boolean?,
        val connected: Boolean?,
        val mode: String,
        val option: String,
        val targets: JSONObject,
        val raw: JSONObject
    )

    private fun JSONObject.dbl(k: String): Double? =
        if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

    fun getStatus(id: String): Status {
        val e = post("/api/endpoints/getEndpoint", JSONObject().put("endpoint", id))
            .optJSONObject("endpoint") ?: throw ApiException("Cihaz bilgisi gelmedi")

        val combi = e.opt("combiState")?.toString()?.lowercase()
        val connected = e.optJSONObject("masterDevice")?.optJSONObject("modelDevice")
            ?.takeIf { it.has("isConnected") }?.optBoolean("isConnected")
            ?: if (e.has("isConnected")) e.optBoolean("isConnected") else null

        return Status(
            name = e.optString("name", "Termostat"),
            temperature = e.dbl("temperature"),
            humidity = e.dbl("humidity"),
            targetTemperature = e.dbl("targetTemperature"),
            combiOn = when (combi) { null -> null; "on", "true", "1" -> true; else -> false },
            connected = connected,
            mode = e.optString("mode", ""),
            option = e.optString("option", ""),
            targets = e.optJSONObject("targetTemperatures") ?: JSONObject(),
            raw = e
        )
    }

    /** mode: manual | schedule | auto ; option: home | away | sleep | custom | frozen */
    fun setMode(id: String, mode: String, option: String? = null) {
        val b = JSONObject().put("endpoint", id).put("mode", mode)
        if (option != null) b.put("option", option)
        post("/api/endpoints/setMode", b)
    }

    fun setTargetTemperatures(id: String, targets: JSONObject) {
        post("/api/endpoints/setTargetTemperatures",
            JSONObject().put("endpoint", id).put("targetTemperatures", targets))
    }
}
