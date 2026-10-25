package br.apuracao

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.*
import android.widget.RemoteViews
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Cand(val nome: String, val votos: Long, val pct: Double)
data class Resultado(val cands: List<Cand>, val urnas: Double, val hora: String)

object Tse {
    // 2º turno 2026 = eleição 6258 (códigos vêm de resultados.tse.jus.br/oficial/comum/config/ele-c.json)
    const val URL_BR = "https://resultados.tse.jus.br/oficial/ele2026/6258/dados/br/br-c0001-e006258-u.json"

    private fun num(s: String): Double {
        val t = s.trim()
        return (if (t.contains(',')) t.replace(".", "").replace(',', '.') else t).toDoubleOrNull() ?: 0.0
    }

    private fun achar(o: Any?, k: String): Any? {
        when (o) {
            is JSONObject -> {
                if (o.has(k)) return o.get(k)
                for (x in o.keys()) achar(o.get(x), k)?.let { return it }
            }
            is JSONArray -> for (i in 0 until o.length()) achar(o.get(i), k)?.let { return it }
        }
        return null
    }

    fun buscar(): Resultado {
        val c = URL(URL_BR).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
        c.setRequestProperty("Accept", "application/json, text/plain, */*")
        c.setRequestProperty("Referer", "https://resultados.tse.jus.br/")
        c.setRequestProperty("Cache-Control", "no-cache")
        val cod = c.responseCode
        if (cod == 404) throw FileNotFoundException()
        if (cod != 200) throw Exception("HTTP $cod")
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val arr = achar(j, "cand") as? JSONArray
            ?: throw Exception("campo cand não encontrado (" + j.keys().asSequence().take(6).joinToString(",") + ")")
        val l = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Cand(it.optString("nm"), it.optString("vap").filter { ch -> ch.isDigit() }.toLongOrNull() ?: 0L, num(it.optString("pvap")))
        }.sortedByDescending { it.votos }
        val pst = num(achar(j, "pst")?.toString() ?: "")
        return Resultado(l, pst, (j.optString("dg") + " " + j.optString("hg")).trim())
    }
}

object Store {
    private val br = Locale.forLanguageTag("pt-BR")
    fun p(c: Context) = c.getSharedPreferences("ap", 0)
    fun salvar(c: Context, r: Resultado) {
        val e = p(c).edit()
        val agora = SimpleDateFormat("HH:mm:ss", br).format(Date())
        r.cands.take(2).forEachIndexed { i, k ->
            e.putString("n$i", k.nome).putString("p$i", String.format(br, "%.2f%%", k.pct))
                .putString("v$i", String.format(br, "%,d votos", k.votos)).putFloat("f$i", k.pct.toFloat())
        }
        if (r.cands.size >= 2) e.putString("dif", String.format(br, "Vantagem de %,d votos", r.cands[0].votos - r.cands[1].votos))
        e.putFloat("uf", r.urnas.toFloat()).putString("agora", agora)
            .putString("urnas", String.format(br, "%.2f%% das urnas apuradas", r.urnas))
            .putString("st", String.format(br, "%.2f%% das urnas · atualizado %s", r.urnas, agora))
            .putString("aviso", "").apply()
    }
    private fun curto(n: String?) = (n ?: "").trim().split(" ").firstOrNull().orEmpty()
        .lowercase().replaceFirstChar { it.uppercase() }
    fun titulo(c: Context): String {
        val p = p(c)
        if (p.getString("n0", null) == null) return "Apuração 2026"
        return "${curto(p.getString("n0", ""))} ${p.getString("p0", "")} × ${curto(p.getString("n1", ""))} ${p.getString("p1", "")}"
    }
    fun detalhe(c: Context): String {
        val p = p(c)
        if (p.getString("n0", null) == null) return "Aguardando dados do TSE…"
        return "${p.getString("n0", "")}: ${p.getString("p0", "")} (${p.getString("v0", "")})\n" +
            "${p.getString("n1", "")}: ${p.getString("p1", "")} (${p.getString("v1", "")})\n" +
            (p.getString("st", "") ?: "")
    }
}

class ResultWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, views(ctx)) }
    }
    companion object {
        fun views(c: Context): RemoteViews {
            val p = Store.p(c)
            val v = RemoteViews(c.packageName, R.layout.widget)
            v.setTextViewText(R.id.n0, p.getString("n0", "Aguardando apuração…"))
            v.setTextViewText(R.id.p0, p.getString("p0", ""))
            v.setTextViewText(R.id.v0, p.getString("v0", ""))
            v.setTextViewText(R.id.n1, p.getString("n1", ""))
            v.setTextViewText(R.id.p1, p.getString("p1", ""))
            v.setTextViewText(R.id.v1, p.getString("v1", ""))
            val a = p.getFloat("f0", 0f); val b = p.getFloat("f1", 0f)
            v.setProgressBar(R.id.bar, 1000, if (a + b > 0) (a / (a + b) * 1000).toInt() else 0, false)
            v.setProgressBar(R.id.bar_u, 1000, (p.getFloat("uf", 0f) * 10).toInt(), false)
            val aviso = p.getString("aviso", "") ?: ""
            v.setTextViewText(R.id.st, aviso.ifEmpty { p.getString("st", "Toque para abrir o app") })
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            return v
        }
        fun refresh(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c, ResultWidget::class.java)).forEach { m.updateAppWidget(it, views(c)) }
        }
    }
}

class ApuracaoService : Service() {
    @Volatile private var ativo = false
    override fun onBind(i: Intent?): IBinder? = null

    private fun notif(titulo: String, texto: String, grande: String = texto): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "ap")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setColor(0xFF26A69A.toInt())
            .setContentTitle(titulo).setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(grande))
            .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC).build()
    }

    private fun atualizar() {
        val p = Store.p(this)
        try {
            Store.salvar(this, Tse.buscar())
        } catch (e: FileNotFoundException) {
            p.edit().putString("aviso", "Aguardando o TSE liberar a apuração…").apply()
        } catch (e: Exception) {
            p.edit().putString("aviso", "Erro: " + (e.message ?: e.javaClass.simpleName).take(90)).apply()
        }
        val aviso = p.getString("aviso", "") ?: ""
        getSystemService(NotificationManager::class.java)
            .notify(1, notif(Store.titulo(this), aviso.ifEmpty { p.getString("urnas", "") ?: "" }, Store.detalhe(this)))
        ResultWidget.refresh(this)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ap", "Apuração", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notif(Store.titulo(this), "Acompanhando a apuração…", Store.detalhe(this)))
        if (i?.getBooleanExtra("agora", false) == true) Thread { atualizar() }.start()
        if (!ativo) {
            ativo = true
            Thread { while (ativo) { atualizar(); Thread.sleep(60_000) } }.start()
        }
        return START_STICKY
    }
    override fun onDestroy() { ativo = false }
}

class MainActivity : Activity() {
    private val br = Locale.forLanguageTag("pt-BR")
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { mostrar(); h.postDelayed(this, 3000) } }
    private fun tv(id: Int) = findViewById<TextView>(id)
    private fun pb(id: Int) = findViewById<ProgressBar>(id)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = 0xFF101214.toInt()
        window.navigationBarColor = 0xFF101214.toInt()
        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        startForegroundService(Intent(this, ApuracaoService::class.java))
        findViewById<View>(R.id.btn).setOnClickListener {
            tv(R.id.status).text = "Atualizando…"
            startForegroundService(Intent(this, ApuracaoService::class.java).putExtra("agora", true))
        }
    }
    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun mostrar() {
        val p = Store.p(this)
        fun s(k: String, d: String = "") = p.getString(k, d) ?: d
        tv(R.id.n0).text = s("n0", "Aguardando apuração…"); tv(R.id.p0).text = s("p0", "–"); tv(R.id.v0).text = s("v0")
        tv(R.id.n1).text = s("n1"); tv(R.id.p1).text = s("p1", "–"); tv(R.id.v1).text = s("v1")
        pb(R.id.b0).progress = (p.getFloat("f0", 0f) * 10).toInt()
        pb(R.id.b1).progress = (p.getFloat("f1", 0f) * 10).toInt()
        pb(R.id.bu).progress = (p.getFloat("uf", 0f) * 10).toInt()
        tv(R.id.up).text = String.format(br, "%.2f%%", p.getFloat("uf", 0f))
        tv(R.id.dif).text = s("dif")
        val aviso = s("aviso")
        tv(R.id.status).apply {
            text = aviso.ifEmpty { "Atualizado às " + s("agora", "–") + " · automático a cada 60 s" }
            setTextColor(if (aviso.isEmpty()) 0xFF9AA3AB.toInt() else 0xFFFFB300.toInt())
        }
    }
}
