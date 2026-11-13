package io.github.almeida96.cronfold

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant

internal object Html {
    private const val visible = 60

    fun render(report: JsonNode): String = buildString {
        append("""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Schedule comparison · CronFold</title>
        <style>
        :root{color-scheme:light;--paper:#f4f3ed;--ink:#1e322d;--muted:#4b5e55;--rule:#c9cfc4;--red:#9a3725}
        *{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:16px/1.48 "Inter",system-ui,sans-serif}
        main{max-width:70rem;margin:auto;padding:0 clamp(1rem,5vw,4rem) 4rem}nav{border-top:5px solid #375846;border-bottom:1px solid var(--rule);padding:1rem 0;display:flex;align-items:center;gap:1rem}
        nav strong{font-size:1.25rem;letter-spacing:-.05em}nav a{margin-left:auto}a{color:#245640;text-underline-offset:.2em}
        header{padding:2.6rem 0 1.5rem}h1{font:550 clamp(2.2rem,5vw,3.6rem)/1.05 Georgia,serif;letter-spacing:-.052em;margin:.65rem 0}
        h2{font-size:1.35rem;letter-spacing:-.035em;margin:0 0 .65rem}p{margin:.3rem 0}.meta{color:var(--muted)}code{font: .9em/1.4 "IBM Plex Mono",ui-monospace,monospace;overflow-wrap:anywhere}
        section{padding:1.3rem 0;border-top:1px solid var(--rule)}.tag{display:inline-block;padding:.16rem .5rem;border-radius:.2rem;font-size:.77rem;font-weight:750;letter-spacing:.05em;background:#deeadf;color:#224630}
        .conflict{background:#f9dbcf;color:var(--red)}.change{background:#f1e3ba;color:#655012}
        .lead{background:#fffef9;border:1px solid var(--rule);border-left:4px solid var(--red);border-radius:.15rem;padding:1rem 1.2rem;margin:.4rem 0 1.4rem}
        .lead h2{margin:.3rem 0}.scroll{overflow-x:auto}table{width:100%;border-collapse:collapse;text-align:left;font-size:.92rem}
        th,td{padding:.5rem .6rem;border-bottom:1px solid var(--rule);vertical-align:top}th{color:var(--muted);font-size:.85rem}th:first-child,td:first-child{padding-left:0}
        .nowrap{white-space:nowrap}details{padding:.6rem 0;border-top:1px solid var(--rule)}summary{cursor:pointer}small{color:var(--muted)}
        </style></head><body><main><nav aria-label="Report"><strong>CronFold</strong><a href="report.json">JSON evidence</a></nav>""")
        val state = report["status"].asText()
        val conflicts = report["conflicts"]
        val changes = report["changes"]
        val jobs = report["jobs"]
        val intro = conflicts.firstOrNull { it["classification"].asText() == "introduced" }
        val title = when (state) {
            "CONFLICT" -> "New overlap on a shared resource"
            "CHANGE" -> "The schedule changes within this comparison"
            else -> "No new schedule change"
        }
        append("<header><span class=\"tag ${state.lowercase(java.util.Locale.ROOT)}\">$state</span><h1>$title</h1>")
        append("<p class=\"meta\">").append(esc(report.at("/window/start").asText())).append(" to ")
            .append(esc(report.at("/window/end").asText())).append(" · ").append(jobs.size()).append(" jobs</p></header>")
        if (intro != null) {
            val duration = Duration.between(Instant.parse(intro["start"].asText()), Instant.parse(intro["end"].asText()))
            val length = if (duration.nano == 0 && duration.seconds % 60L == 0L) "${duration.toMinutes()} min" else duration.toString()
            append("<article class=\"lead\"><p>Introduced contention · <code>").append(v(intro,"resource")).append("</code></p>")
            append("<h2>").append(v(intro,"left")).append(" &amp; ").append(v(intro,"right")).append("</h2><p><code>")
            append(v(intro,"start")).append(" – ").append(v(intro,"end")).append("</code> · ").append(length).append("</p></article>")
        }
        append("<section><h2>Runs by job</h2><div class=\"scroll\"><table><thead><tr><th>Job</th><th>Baseline</th><th>Candidate</th><th>Added</th><th>Removed</th></tr></thead><tbody>")
        for (job in jobs) {
            append("<tr><td><code>").append(v(job,"id")).append("</code></td>")
            for (field in listOf("beforeCount","afterCount","added","removed")) append("<td>").append(v(job,field)).append("</td>")
            append("</tr>")
        }
        append("</tbody></table></div></section>")
        if (!changes.isEmpty) {
            append("<section><h2>Changed runs</h2><div class=\"scroll\"><table><thead><tr><th>Change</th><th>Job</th><th>Instant (UTC)</th><th>Scheduled local time</th><th>Resolution</th></tr></thead><tbody>")
            for (row in changes.take(visible)) {
                append("<tr><td>").append(v(row,"kind")).append("</td><td>").append(v(row,"job")).append("</td><td class=\"nowrap\"><code>")
                append(v(row,"instant")).append("</code></td><td><code>").append(v(row,"local")).append(" (").append(v(row,"offset"))
                append(")</code></td><td>").append(v(row,"resolution")).append("</td></tr>")
            }
            append("</tbody></table></div>"); more(changes.size()); append("</section>")
        }
        if (!conflicts.isEmpty) {
            append("<section><h2>Resource conflicts</h2><div class=\"scroll\"><table><thead><tr><th>Change</th><th>Resource</th><th>Jobs</th><th>Start (UTC)</th><th>End (UTC)</th></tr></thead><tbody>")
            for (row in conflicts.take(visible)) {
                append("<tr><td>").append(v(row,"classification")).append("</td><td>").append(v(row,"resource")).append("</td><td>")
                append(v(row,"left")).append(" / ").append(v(row,"right")).append("</td><td><code>").append(v(row,"start"))
                append("</code></td><td><code>").append(v(row,"end")).append("</code></td></tr>")
            }
            append("</tbody></table></div>"); more(conflicts.size()); append("</section>")
        }
        append("<section><h2>Calendar evidence</h2>")
        var available = false
        for (job in jobs) {
            val before = job["beforeEvents"]; val after = job["afterEvents"]
            if (before.isEmpty && after.isEmpty) continue
            available = true
            append("<details><summary><code>").append(v(job,"id")).append("</code> · baseline ").append(before.size())
            append(" / candidate ").append(after.size()).append("</summary>")
            for ((label, events) in listOf("Baseline" to before,"Candidate" to after)) {
                for (e in events.take(visible)) {
                    append("<p>").append(label).append(" · ").append(v(e,"type")).append(" · <code>").append(v(e,"local"))
                    append("</code> · ").append(v(e,"before")).append(" → ").append(v(e,"after")).append(" · ")
                    append(e["emitted"].joinToString(", ") { esc(it.asText()) }.ifEmpty { "no run in the window" }).append("</p>")
                }
                more(events.size())
            }
            append("</details>")
        }
        if (!available) append("<p>No matching local times fell inside a clock transition in this window.</p>")
        append("</section></main></body></html>")
    }

    private fun StringBuilder.more(size:Int) {
        if (size > visible) append("<p><small>Showing the first $visible of $size. <a href=\"report.json\">Read all evidence</a>.</small></p>")
    }
    private fun v(row:JsonNode,key:String) = esc(row.path(key).asText())
    private fun esc(text:String) = text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
}
