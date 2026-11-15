package io.quartetqe.generator;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The review page as one self-contained HTML file in Quartet QE's look: no scripts, no fonts or images to fetch, so it opens
 * anywhere, works offline, follows the reader's light or dark setting and prints cleanly. The markdown review stays for pull
 * requests; this is the page a person reads.
 */
final class BrandedReport {
    record Test(String name, String device, String outcome, double seconds, String failure) {}

    record Criterion(String id, String text, List<Test> tests) {}

    record Principle(String name, String status, List<String> notes) {}

    record Fault(String description, String result) {}

    record Model(String storyId, String title, boolean approved, List<String> attention, List<Criterion> criteria,
                 List<Principle> principles, List<String> questions, List<Fault> faults, double detectionRate, int falseAlarms,
                 int modelTurns, long tokens, int selfCorrections, boolean ran, int passed, int failed, int skipped, double seconds) {}

    private BrandedReport() {}

    static String render(Model m) {
        long covered = m.criteria().stream().filter(c -> !c.tests().isEmpty()).count();
        long criteriaPassing = m.criteria().stream().filter(c -> !c.tests().isEmpty() && c.tests().stream().allMatch(t -> t.outcome().equals("PASSED"))).count();
        int total = m.passed() + m.failed() + m.skipped();
        String generated = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH));

        StringBuilder h = new StringBuilder(14_000);
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Review ").append(esc(m.storyId())).append(" · Quartet QE</title><style>").append(CSS).append("</style></head><body><div class=\"wrap\">");

        // Header
        h.append("<header class=\"top\"><div class=\"brand\"><span class=\"mark\">Q<b>E</b></span><span class=\"word\">Quartet <b>QE</b></span></div>")
                .append("<div class=\"meta\">Review · ").append(esc(m.storyId())).append(" · ").append(esc(generated)).append("</div></header>");

        // Verdict
        h.append("<section class=\"hero\"><div class=\"eyebrow\">Review</div><h1 class=\"verdict\">")
                .append(m.approved() ? "Ready <span class=\"tag\">to merge.</span>" : "Needs <span class=\"tag warn\">your attention.</span>").append("</h1>")
                .append("<p class=\"story\"><span class=\"id\">").append(esc(m.storyId())).append("</span> ").append(esc(m.title())).append("</p>");
        h.append("<p class=\"lede\">");
        if (m.ran()) {
            h.append("<strong>").append(m.passed()).append(" of ").append(total).append("</strong> tests passed. ");
        } else {
            h.append("The tests were written but not run. ");
        }
        h.append("<strong>").append(covered).append(" of ").append(m.criteria().size()).append("</strong> acceptance criteria are covered.");
        if (m.faults() != null) h.append(" <strong>").append(Math.round(m.detectionRate() * 100)).append("%</strong> of planted bugs were caught.");
        h.append("</p></section>");

        // Stat tiles
        h.append("<section class=\"tiles\">");
        if (m.ran()) {
            tile(h, m.passed() + (total == m.passed() ? "" : " / " + total), "tests passed", m.failed() > 0 ? "bad" : "good");
            if (m.failed() > 0) tile(h, String.valueOf(m.failed()), "failed", "bad");
            if (m.skipped() > 0) tile(h, String.valueOf(m.skipped()), "skipped", "dim");
        } else {
            tile(h, "—", "not run yet", "dim");
        }
        tile(h, criteriaPassing + " / " + m.criteria().size(), "criteria proven", criteriaPassing == m.criteria().size() ? "good" : "warn");
        if (m.faults() != null) tile(h, Math.round(m.detectionRate() * 100) + "%", "planted bugs caught", m.detectionRate() >= 1.0 ? "good" : "warn");
        if (m.ran() && m.seconds() > 0) tile(h, m.seconds() >= 1 ? String.format(Locale.ENGLISH, "%.1f s", m.seconds()) : Math.round(m.seconds() * 1000) + " ms", "run time", "dim");
        tile(h, String.valueOf(m.modelTurns()), "AI turns to write them", "dim");
        h.append("</section>");

        // Attention
        h.append("<section><h2>Needs your attention</h2>");
        if (m.attention().isEmpty()) h.append("<div class=\"clear\"><span class=\"dot good\"></span>Nothing. Every check passed.</div>");
        else for (String item : m.attention()) h.append("<div class=\"alert\"><span class=\"dot bad\"></span><div>").append(inline(item)).append("</div></div>");
        h.append("</section>");

        // Criteria
        h.append("<section><h2>What was tested</h2>");
        for (Criterion c : m.criteria()) {
            String state = c.tests().isEmpty() ? "none" : c.tests().stream().anyMatch(t -> t.outcome().equals("FAILED")) ? "bad"
                    : c.tests().stream().allMatch(t -> t.outcome().equals("PASSED")) ? "good" : "dim";
            String label = c.tests().isEmpty() ? "Not covered" : state.equals("bad") ? "Failed" : state.equals("good") ? "Proven" : "Not run";
            h.append("<article class=\"ac s-").append(state).append("\"><div class=\"achead\"><span class=\"acid\">").append(esc(c.id())).append("</span><p>")
                    .append(esc(c.text())).append("</p><span class=\"chip ").append(state).append("\">").append(label).append("</span></div>");
            for (Test t : c.tests()) {
                h.append("<div class=\"trow\"><code>").append(esc(t.name())).append("</code>");
                if (!t.device().isBlank()) h.append("<span class=\"pill\">").append(esc(t.device())).append("</span>");
                h.append("<span class=\"grow\"></span>");
                if (t.seconds() > 0) h.append("<span class=\"time\">").append(t.seconds() >= 1 ? String.format(Locale.ENGLISH, "%.1f s", t.seconds()) : Math.round(t.seconds() * 1000) + " ms").append("</span>");
                h.append("<span class=\"chip ").append(chip(t.outcome())).append("\">").append(label(t.outcome())).append("</span></div>");
                if (t.failure() != null && !t.failure().isBlank()) h.append("<pre class=\"fail\">").append(esc(t.failure())).append("</pre>");
            }
            h.append("</article>");
        }
        h.append("</section>");

        // Questions
        if (!m.questions().isEmpty()) {
            h.append("<section><h2>Questions for the story author</h2><ul class=\"questions\">");
            m.questions().forEach(q -> h.append("<li>").append(esc(q)).append("</li>"));
            h.append("</ul></section>");
        }

        // Planted bugs
        if (m.faults() != null) {
            h.append("<section><h2>Planted bugs</h2><p class=\"note\">Bugs were put into the system on purpose to prove the tests would notice. ")
                    .append(Math.round(m.detectionRate() * 100)).append("% caught, ").append(m.falseAlarms()).append(" false alarm(s).</p><div class=\"faults\">");
            for (Fault f : m.faults()) {
                String state = f.result().equals("MISSED") || f.result().equals("FALSE ALARM") ? "bad" : "good";
                h.append("<div class=\"trow\"><span>").append(esc(f.description())).append("</span><span class=\"grow\"></span><span class=\"chip ")
                        .append(state).append("\">").append(esc(f.result().equals("OK") ? "Tests still passed, as they should" : cap(f.result()))).append("</span></div>");
            }
            h.append("</div></section>");
        }

        // Principles
        h.append("<section><h2>How this was checked</h2><p class=\"note\">Every test is checked against the team's engineering principles before it is run.</p><div class=\"grid\">");
        for (Principle p : m.principles()) {
            String state = p.status().equals("FAIL") ? "bad" : p.status().equals("NOTE") ? "warn" : "good";
            h.append("<details class=\"pr s-").append(state).append("\"").append(p.notes().isEmpty() ? " data-empty" : "").append("><summary><span class=\"dot ").append(state).append("\"></span><span>")
                    .append(esc(p.name())).append("</span><em>").append(cap(p.status())).append("</em></summary>");
            if (!p.notes().isEmpty()) {
                h.append("<ul>");
                p.notes().forEach(n -> h.append("<li>").append(esc(n)).append("</li>"));
                h.append("</ul>");
            }
            h.append("</details>");
        }
        h.append("</div><p class=\"note foot\">The AI used ").append(m.modelTurns()).append(" model turns and ").append(m.tokens()).append(" tokens. Its own checks sent back ")
                .append(m.selfCorrections()).append(" draft(s) before review.</p></section>");

        // Footer
        h.append("<footer><div><strong>A person approves.</strong> Merge the pull request when you are happy. Quartet QE never merges for you.</div>")
                .append("<div class=\"fine\">Quartet QE ").append(esc(McpServer.version())).append(" · written by AI, checked against your principles, approved by a human.</div></footer>");
        h.append("</div></body></html>");
        return h.toString();
    }

    private static void tile(StringBuilder h, String value, String label, String tone) {
        h.append("<div class=\"tile\"><div class=\"num ").append(tone).append("\">").append(esc(value)).append("</div><div class=\"lbl\">").append(esc(label)).append("</div></div>");
    }

    private static String chip(String outcome) {
        return switch (outcome) {
            case "PASSED" -> "good";
            case "FAILED" -> "bad";
            default -> "dim";
        };
    }

    private static String label(String outcome) {
        return switch (outcome) {
            case "PASSED" -> "Passed";
            case "FAILED" -> "Failed";
            case "SKIPPED" -> "Skipped";
            default -> "Not run";
        };
    }

    private static String cap(String text) {
        String lower = text.toLowerCase(Locale.ENGLISH);
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Escapes, then turns the small markdown the review uses (bold and code) into markup. */
    static String inline(String text) {
        return esc(text).replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>").replaceAll("`(.+?)`", "<code>$1</code>");
    }

    static String esc(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS = """
        :root{--bg:#07090c;--panel:#0f1418;--panel2:#141b20;--line:#222c33;--text:#f3f6f4;--dim:#8a9aa2;--lime:#c6ff3d;--limeInk:#07090c;--good:#c6ff3d;--bad:#ff6b5e;--warn:#ffc857;--cyan:#3de0ff;--glow:rgba(198,255,61,.10)}
        @media (prefers-color-scheme:light){:root{--bg:#f5f6f2;--panel:#fff;--panel2:#f0f2ec;--line:#dde1d8;--text:#0b0f12;--dim:#5a6870;--good:#3d7a00;--bad:#c4372b;--warn:#a86b00;--cyan:#0a7c99;--glow:rgba(100,160,0,.10)}}
        *{box-sizing:border-box;margin:0;padding:0}
        body{background:var(--bg);color:var(--text);font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;background-image:radial-gradient(900px 500px at 85% -10%,var(--glow),transparent 70%);background-repeat:no-repeat}
        .wrap{max-width:1080px;margin:0 auto;padding:32px 24px 72px}
        code,pre,.mono,.meta,.eyebrow,.acid,.pill,.chip,.time,.id{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
        .top{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap;padding-bottom:28px;border-bottom:1px solid var(--line)}
        .brand{display:flex;align-items:center;gap:12px}
        .mark{width:40px;height:40px;border-radius:10px;background:#07090c;color:#f3f6f4;font:900 italic 21px/40px -apple-system,Helvetica,Arial,sans-serif;letter-spacing:-.06em;text-align:center;border:1px solid var(--line)}
        .mark b{color:var(--lime)}
        .word{font:900 italic 24px/1 -apple-system,Helvetica,Arial,sans-serif;letter-spacing:-.04em}.word b{color:var(--good)}
        .meta{font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--dim)}
        .hero{padding:56px 0 8px}
        .eyebrow{font-size:12px;letter-spacing:.18em;text-transform:uppercase;color:var(--good);font-weight:700;margin-bottom:14px}
        .verdict{font:900 clamp(44px,9vw,96px)/.98 -apple-system,Helvetica,Arial,sans-serif;letter-spacing:-.05em}
        .tag{display:inline-block;background:var(--lime);color:var(--limeInk);padding:0 .14em .05em;font-style:italic}.tag.warn{background:var(--warn);color:var(--limeInk)}
        .story{margin-top:22px;font-size:22px;font-weight:600;letter-spacing:-.01em}.story .id{font-size:14px;background:var(--panel2);border:1px solid var(--line);padding:3px 9px;border-radius:7px;margin-right:8px;vertical-align:3px}
        .lede{margin-top:12px;color:var(--dim);font-size:17px}.lede strong{color:var(--text)}
        .tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin:34px 0 6px}
        .tile{background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:18px 20px}
        .num{font:800 36px/1.05 -apple-system,Helvetica,Arial,sans-serif;letter-spacing:-.04em}.lbl{margin-top:6px;font-size:13px;color:var(--dim)}
        .good{color:var(--good)}.bad{color:var(--bad)}.warn{color:var(--warn)}.dim{color:var(--dim)}
        section{margin-top:44px}
        h2{font:700 12px/1 ui-monospace,SFMono-Regular,Menlo,monospace;letter-spacing:.18em;text-transform:uppercase;color:var(--dim);margin-bottom:16px}
        .note{color:var(--dim);font-size:14px;margin:-4px 0 14px}.note.foot{margin:16px 0 0}
        .clear,.alert{display:flex;gap:12px;align-items:flex-start;background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:16px 18px;margin-bottom:10px}
        .alert{border-color:color-mix(in srgb,var(--bad) 55%,var(--line))}.alert code{background:var(--panel2);padding:1px 6px;border-radius:5px}
        .dot{flex:none;width:10px;height:10px;border-radius:50%;margin-top:7px;background:var(--dim)}.dot.good{background:var(--good)}.dot.bad{background:var(--bad)}.dot.warn{background:var(--warn)}
        .ac{background:var(--panel);border:1px solid var(--line);border-left:4px solid var(--dim);border-radius:14px;margin-bottom:12px;overflow:hidden}
        .ac.s-good{border-left-color:var(--good)}.ac.s-bad{border-left-color:var(--bad)}.ac.s-none{border-left-color:var(--warn)}
        .achead{display:flex;gap:14px;align-items:flex-start;padding:16px 18px}.achead p{flex:1;font-weight:600}
        .acid{flex:none;font-size:12px;font-weight:700;background:var(--panel2);border:1px solid var(--line);padding:4px 9px;border-radius:7px;margin-top:2px;color:var(--text)}
        .trow{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:11px 18px;border-top:1px solid var(--line);font-size:14px}
        .trow code{font-size:13px;word-break:break-all}.grow{flex:1}
        .pill{font-size:11px;color:var(--cyan);border:1px solid color-mix(in srgb,var(--cyan) 45%,var(--line));padding:2px 8px;border-radius:999px}
        .time{font-size:12px;color:var(--dim)}
        .chip{flex:none;font-size:11px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;padding:4px 10px;border-radius:999px;border:1px solid var(--line)}
        .chip.good{color:var(--good);border-color:color-mix(in srgb,var(--good) 50%,var(--line));background:color-mix(in srgb,var(--good) 9%,transparent)}
        .chip.bad{color:var(--bad);border-color:color-mix(in srgb,var(--bad) 55%,var(--line));background:color-mix(in srgb,var(--bad) 10%,transparent)}
        .chip.none{color:var(--warn);border-color:color-mix(in srgb,var(--warn) 55%,var(--line))}.chip.dim{color:var(--dim)}
        pre.fail{margin:0;padding:12px 18px;background:var(--panel2);color:var(--bad);font-size:12px;white-space:pre-wrap;word-break:break-word}
        .questions{list-style:none}.questions li{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:12px 16px;margin-bottom:8px;color:var(--dim)}
        .faults{background:var(--panel);border:1px solid var(--line);border-radius:14px}.faults .trow:first-child{border-top:0}
        .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(310px,1fr));gap:10px}
        .pr{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:12px 14px}
        .pr summary{list-style:none;display:flex;gap:10px;align-items:center;cursor:pointer;font-size:14px}.pr summary::-webkit-details-marker{display:none}
        .pr summary .dot{margin-top:0}.pr summary em{margin-left:auto;font-style:normal;font-size:11px;letter-spacing:.06em;text-transform:uppercase;color:var(--dim)}
        .pr[data-empty] summary{cursor:default}.pr ul{margin:10px 0 2px 20px;font-size:13px;color:var(--dim)}
        footer{margin-top:56px;padding-top:24px;border-top:1px solid var(--line);color:var(--dim);font-size:14px;display:grid;gap:8px}footer strong{color:var(--text)}.fine{font-size:12px}
        @media print{body{background:#fff;color:#000;background-image:none}.tile,.ac,.pr,.faults,.alert,.clear,.questions li{break-inside:avoid;box-shadow:none}:root{--bg:#fff;--panel:#fff;--panel2:#f3f3f3;--line:#d6d6d6;--text:#000;--dim:#555;--good:#2d6a00;--bad:#b3261e;--warn:#8a5a00;--cyan:#0a6d85}}
        """;
}
