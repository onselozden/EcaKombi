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

    /** JSON ağacında anahtarı derinlemesine arar (iç içe nesneler/diziler dahil). */
    private fun deepFind(node: Any?, key: String): Any? {
        when (node) {
            is JSONObject -> {
                node.keys().forEach { k ->
                    if (k.equals(key, true) && !node.isNull(k)) return node.get(k)
                }
                node.keys().forEach { k -> deepFind(node.opt(k), key)?.let { return it } }
            }
            is org.json.JSONArray -> for (i in 0 until node.length()) deepFind(node.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun JSONObject.dbl(vararg keys: String): Double? {
        for (k in keys) {
            val v = deepFind(this, k) ?: continue
            when (v) {
                is Number -> return v.toDouble()
                is String -> v.toDoubleOrNull()?.let { return it }
                is JSONObject -> listOf("value", "current", "temperature").forEach { f ->
                    if (v.has(f)) v.optDouble(f).takeIf { !it.isNaN() }?.let { return it }
                }
            }
        }
        return null
    }

    fun getStatus(id: String): Status {
        val r = post("/api/endpoints/getEndpoint", JSONObject().put("endpoint", id))
        val e = r.optJSONObject("endpoint") ?: throw ApiException("Cihaz bilgisi gelmedi")

        val combi = e.optString("combiState", "").lowercase()
        val connected = deepFind(r, "isConnected")?.toString()?.let { it == "true" }
            ?: if (combi == "connectionlost") false else null
        val targets = JSONObject()
        listOf("home", "away", "sleep", "custom").forEach { k ->
            e.dbl(k + "Temperature")?.let { targets.put(k, it) }
        }

        return Status(
            name = e.optString("name", "Termostat"),
            temperature = r.dbl("temperature", "currentTemperature", "roomTemperature"),
            humidity = r.dbl("humidity", "currentHumidity"),
            targetTemperature = r.dbl("targetTemperature"),
            combiOn = when (combi) { "", "connectionlost" -> null; "on", "true", "1" -> true; else -> false },
            connected = connected,
            mode = e.optString("mode", ""),
            option = e.optString("option", ""),
            targets = targets,
            raw = r
        )
    }

    /** mode: manual | schedule | auto */
    fun setMode(id: String, mode: String) {
        post("/api/endpoints/setMode", JSONObject().put("endpoint", id).put("mode", mode))
    }

    /** option: home | away | sleep | custom | frozen */
    fun setOption(id: String, option: String) {
        post("/api/endpoints/setOption", JSONObject().put("endpoint", id).put("option", option))
    }

    fun setTargetTemperatures(id: String, targets: JSONObject) {
        post("/api/endpoints/setTargetTemperatures",
            JSONObject().put("endpoint", id).put("targetTemperatures", targets))
    }
}
