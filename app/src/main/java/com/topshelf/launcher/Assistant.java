package com.topshelf.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The assistant (Settings › System › Assistant): Claude, with your own Anthropic API key, kept on the TV only.
 *
 * Voice requests: what you say to the remote's mic that sounds like a request ("put on the Falcons game", "something
 * funny under two hours", "what did I miss") goes to Claude with what TopShelf knows right now (the time, today's
 * games of your sports, the game you're watching), and comes back as one thing to do: play a game or a channel,
 * search, offer a few titles, catch you up on the game, or just answer. Anything else said is searched as before.
 *
 * "What did I miss?": the game you're watching (Live TV, hold OK, or by voice) — ESPN's account of it so far (score,
 * scoring plays, the last drives or plays) summed up in three sentences.
 *
 * What's sent: your words, the time, the names of the games, and ESPN's public account of a game; for the "For
 * tonight" row, the names of the movies and shows you've watched recently and saved to your list. Never your channel
 * list, logins, playlist, or which live channels you watch.
 */
final class Assistant {
    private Assistant() { }

    private static final String API = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-haiku-5-5";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE); }
    static String key(Context c) { return prefs(c).getString("aiKey", "").trim(); }
    static boolean on(Context c) { return !key(c).isEmpty() && prefs(c).getBoolean("aiVoice", true); }

    /* what sounds like a request rather than a name to search for */
    private static final Pattern ASK = Pattern.compile("^(put on|play|watch|turn on|switch to|change to|go to|open|show me|find me|find something|"
            + "i want|i'd like|recommend|suggest|give me|something|anything|what|who|how|when|where|is there|are there|which|can you|tell me|catch me up)\\b"
            + "|what did i miss|catch me up|something (funny|scary|good|new|light|short)|\\bunder (an|one|two|three|\\d+) hours?\\b");
    static boolean wants(Context c, String said) {
        if (said == null || !on(c)) return false;
        return ASK.matcher(said.trim().toLowerCase(Locale.US)).find();
    }

    // ================================================================== voice requests

    /** what was said: worked out by Claude, then done (main thread in) */
    static void handle(final MainActivity act, final String said) {
        final NativeHome h = act.nativeHome();
        if (h == null) return;
        Diag.ev("assistant: a request (" + said.length() + " characters)");
        h.toast("Asking Claude…");
        final JSONArray games = h.sportsData().gamesForAssistant();
        final JSONObject watching = h.liveWatchingGameJson();
        new Thread(new Runnable() { @Override public void run() {
            JSONObject a;
            try {
                StringBuilder ctx = new StringBuilder();
                ctx.append("Now: ").append(new SimpleDateFormat("EEEE MMMM d, h:mm a", Locale.US).format(new Date())).append('\n');
                ctx.append(watching != null ? "Watching now: " + watching.optString("name", watching.optString("short")) + "\n" : "Not watching a game now.\n");
                ctx.append("Games today and soon (id | league | game | state | start):\n");
                for (int i = 0; i < games.length(); i++) ctx.append(games.optString(i)).append('\n');
                String sys = "You turn a TV viewer's spoken request into ONE action for the TopShelf TV launcher. Reply with only a JSON object, one of:\n"
                        + "{\"do\":\"game\",\"id\":\"<id from the list>\"} to watch a game in the list;\n"
                        + "{\"do\":\"channel\",\"name\":\"<channel name, e.g. ESPN, CNN, Fox Sports 1>\"} to tune a live channel;\n"
                        + "{\"do\":\"search\",\"query\":\"<a movie, show or person's exact name>\"} when they named or described one specific title (name it);\n"
                        + "{\"do\":\"suggest\",\"say\":\"<a few words>\",\"titles\":[\"<3 to 6 real movie or show titles that fit>\"]} for a recommendation;\n"
                        + "{\"do\":\"catchup\"} when they ask what they missed in the game they're watching;\n"
                        + "{\"do\":\"ask\"} for a question or anything to talk about (it's answered separately, with more thought);\n"
                        + "{\"do\":\"say\",\"text\":\"<a few words>\"} only to say you can't do something.\n"
                        + "Speech recognition may mishear names: pick the closest sensible match.";
                String reply = ask(act, sys, ctx + "\nThey said: \"" + said + "\"", 300);
                a = firstJson(reply);
            } catch (final Exception e) {
                MAIN.post(new Runnable() { @Override public void run() { failed(h, e); } });
                return;
            }
            final JSONObject act1 = a;
            MAIN.post(new Runnable() { @Override public void run() { act(act, h, act1, said); } });
        } }, "assistant").start();
    }

    private static void act(MainActivity act, final NativeHome h, JSONObject a, String said) {
        String what = a == null ? "" : a.optString("do");
        Diag.ev("assistant: " + (what.isEmpty() ? "no answer it could use" : what));
        try { h.findClose(); } catch (Throwable ignored) { }
        act.js("window.closeSearch && mode === 'search' && closeSearch()");
        switch (what) {
            case "game": {
                SportsData sd = h.sportsData();
                SportsData.Ev e = sd.find(a.optString("id"));
                if (e != null) { sd.watch(e); return; }
                h.toast("That game isn't on today");
                return;
            }
            case "channel": {
                NativeLive.Ch ch = findChannel(h, a.optString("name"));
                if (ch != null) { h.livePlayUrl(ch.u); return; }
                h.toast("No channel called “" + a.optString("name") + "” in your playlist");
                return;
            }
            case "search": h.searchQuery(a.optString("query"), true); return;
            case "suggest": {
                JSONArray ts = a.optJSONArray("titles");
                if (ts == null || ts.length() == 0) { h.searchQuery(said, true); return; }
                List<String> l = new ArrayList<>(); List<Runnable> r = new ArrayList<>();
                for (int i = 0; i < ts.length() && i < 6; i++) {
                    final String t = ts.optString(i);
                    if (t.isEmpty()) continue;
                    l.add(t); r.add(new Runnable() { @Override public void run() { h.searchQuery(t, true); } });
                }
                h.showMenu(l, r, null, 1f, a.optString("say", "Some ideas"));
                speak(h.getContext(), a.optString("say", "Here are some ideas"));
                return;
            }
            case "catchup": catchUp(h); return;
            case "say": card(h, "Claude", a.optString("text")); speak(h.getContext(), a.optString("text")); return;
            case "ask": answer(h, said); return;
            default: h.searchQuery(said, true);        // (nothing usable: searched, as before)
        }
    }

    private static void failed(NativeHome h, Exception e) {
        String m = String.valueOf(e.getMessage());
        Diag.ev("assistant: failed (" + m.replaceAll("sk-ant-[\\w-]+", "…") + ")");
        h.toast(m.contains("401") ? "The Anthropic API key wasn't accepted (Settings › System › Assistant)"
                : m.contains("429") || m.contains("529") ? "Claude is busy right now, try again in a moment"
                : "Couldn't reach Claude");
    }

    /** a channel by name: the same name first, then one that starts with it, then one that has it (favorites first) */
    private static NativeLive.Ch findChannel(NativeHome h, String name) {
        String q = LiveTv.norm(name);
        if (q.isEmpty()) return null;
        List<NativeLive.Ch> all;
        try { all = NativeLive.shared(h.bridge.live.channelsFile()); } catch (Throwable t) { return null; }
        java.util.Set<String> favs = h.liveFavs();
        NativeLive.Ch best = null; int bestRank = 99;
        for (NativeLive.Ch c : all) {
            String n = LiveTv.norm(c.n);
            int rank = n.equals(q) ? 0 : n.startsWith(q) ? 2 : n.contains(q) ? 4 : 99;
            if (rank == 99) continue;
            if (!favs.contains(c.u)) rank++;
            if (rank < bestRank || (rank == bestRank && best != null && c.n.length() < best.n.length())) { best = c; bestRank = rank; }
            if (bestRank == 0) break;
        }
        return best;
    }

    // ================================================================== questions

    /* A question ("who won the World Series last year?", "how tall is LeBron?", "what's a good wine with pizza?")
       answered by the model chosen in Settings (Sonnet, unless changed), which may search the web for anything
       current. A follow-up within ten minutes carries on the same conversation ("and the year before?"). */
    private static final JSONArray history = new JSONArray();
    private static long historyAt;
    static String model(Context c) {
        String m = prefs(c).getString("aiModel", "sonnet");
        return m.equals("haiku") ? "claude-haiku-5-5" : m.equals("opus") ? "claude-opus-5-5" : "claude-sonnet-5-5";
    }
    static void answer(final NativeHome h, final String said) {
        final Context c = h.getContext();
        card(h, "Claude", "Thinking…");
        final JSONArray games = h.sportsData().gamesForAssistant();
        final JSONObject watching = h.liveWatchingGameJson();
        new Thread(new Runnable() { @Override public void run() {
            String text;
            try {
                StringBuilder sys = new StringBuilder("You are Claude, answering out loud on someone's TV (Google TV, the TopShelf launcher). "
                        + "Answer in two to four short spoken sentences: no lists, no markdown, no links, no emoji. "
                        + "Use web search for anything current (scores, news, schedules, weather, prices). "
                        + "Now: " + new SimpleDateFormat("EEEE MMMM d yyyy, h:mm a", Locale.US).format(new Date()) + ".");
                if (watching != null) sys.append(" They're watching ").append(watching.optString("name", watching.optString("short"))).append('.');
                if (games.length() > 0) { sys.append(" Today's games of their sports:"); for (int i = 0; i < games.length() && i < 25; i++) sys.append("\n").append(games.optString(i)); }
                JSONArray msgs = new JSONArray();
                synchronized (history) {
                    if (System.currentTimeMillis() - historyAt > 10 * 60000L) while (history.length() > 0) history.remove(0);
                    for (int i = 0; i < history.length(); i++) msgs.put(history.get(i));
                }
                msgs.put(new JSONObject().put("role", "user").put("content", said));
                text = call(c, model(c), sys.toString(), msgs, 700, true).trim();
                text = text.replaceAll("\\*\\*|__|#+ ", "").replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
                synchronized (history) {
                    history.put(new JSONObject().put("role", "user").put("content", said));
                    history.put(new JSONObject().put("role", "assistant").put("content", text.isEmpty() ? "…" : text));
                    while (history.length() > 12) history.remove(0);
                    historyAt = System.currentTimeMillis();
                }
                Diag.ev("assistant: answered a question (" + model(c) + ")");
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                Diag.ev("assistant: question failed (" + m.replaceAll("sk-ant-[\\w-]+", "…") + ")");
                text = m.contains("401") ? "The Anthropic API key wasn't accepted (Settings › System › Assistant)." : m.contains("429") || m.contains("529") ? "Claude is busy right now. Try again in a moment." : "Couldn't reach Claude right now.";
            }
            final String t = text.isEmpty() ? "I don't have an answer for that." : text;
            MAIN.post(new Runnable() { @Override public void run() { card(h, "Claude", t); speak(c, t); } });
        } }, "assistant-answer").start();
    }

    // ================================================================== "What did I miss?"

    static void catchUp(final NativeHome h) {
        final JSONObject g = h.liveWatchingGameJson();
        if (g == null) { card(h, "What did I miss?", "TopShelf doesn't know which game this channel is showing. Start the game from Sports and it will."); return; }
        if (key(h.getContext()).isEmpty()) { h.toast("Add an Anthropic API key in Settings › System › Assistant first"); return; }
        final String title = "What you missed · " + g.optString("short", g.optString("name"));
        card(h, title, "Catching up…");
        new Thread(new Runnable() { @Override public void run() {
            String text;
            try {
                String facts = gameFacts(g);
                text = ask(h.getContext(), "You are a sports companion on a TV. Using only the facts given, tell a fan who just tuned in what they missed, "
                        + "in three short sentences: the score and time left, the key moments, and the momentum. No preamble, no lists.", facts, 260).trim();
                Diag.ev("assistant: caught up on a game");
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                Diag.ev("assistant: catch-up failed (" + m.replaceAll("sk-ant-[\\w-]+", "…") + ")");
                text = m.contains("401") ? "The Anthropic API key wasn't accepted (Settings › System › Assistant)." : "Couldn't get the catch-up right now.";
            }
            final String t = text;
            MAIN.post(new Runnable() { @Override public void run() { card(h, title, t); speak(h.getContext(), t); } });
        } }, "assistant-catchup").start();
    }

    /** ESPN's public account of the game so far, as plain lines */
    private static String gameFacts(JSONObject g) throws Exception {
        String url = "https://site.api.espn.com/apis/site/v2/sports/" + g.optString("sport") + "/" + g.optString("league") + "/summary?event=" + g.optString("id");
        byte[] b = HomeImages.http(url, null);
        if (b == null) throw new Exception("ESPN didn't answer");
        JSONObject s = new JSONObject(new String(b, StandardCharsets.UTF_8));
        StringBuilder o = new StringBuilder();
        JSONObject comp = s.optJSONObject("header") == null ? null : s.optJSONObject("header").optJSONArray("competitions") == null ? null : s.optJSONObject("header").optJSONArray("competitions").optJSONObject(0);
        if (comp != null) {
            JSONArray cs = comp.optJSONArray("competitors");
            if (cs != null) for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                JSONObject t = c.optJSONObject("team");
                o.append(c.optString("homeAway")).append(": ").append(t == null ? "?" : t.optString("displayName")).append(' ').append(c.optString("score")).append('\n');
            }
            JSONObject st = comp.optJSONObject("status");
            if (st != null && st.optJSONObject("type") != null) o.append("Status: ").append(st.optJSONObject("type").optString("detail")).append('\n');
        }
        JSONArray sp = s.optJSONArray("scoringPlays");
        if (sp != null && sp.length() > 0) {
            o.append("Scoring plays:\n");
            for (int i = 0; i < sp.length() && i < 30; i++) {
                JSONObject p = sp.optJSONObject(i);
                JSONObject per = p.optJSONObject("period"), clk = p.optJSONObject("clock"), tm = p.optJSONObject("team");
                o.append("- ").append(per == null ? "" : "P" + per.optInt("number") + " ").append(clk == null ? "" : clk.optString("displayValue") + " ")
                 .append(tm == null ? "" : tm.optString("abbreviation") + ": ").append(p.optString("text")).append(" (").append(p.optString("awayScore")).append('-').append(p.optString("homeScore")).append(")\n");
            }
        }
        JSONObject dr = s.optJSONObject("drives");
        JSONArray prev = dr == null ? null : dr.optJSONArray("previous");
        if (prev != null && prev.length() > 0) {
            o.append("Latest drives:\n");
            for (int i = Math.max(0, prev.length() - 6); i < prev.length(); i++) {
                JSONObject d = prev.optJSONObject(i);
                JSONObject tm = d.optJSONObject("team");
                o.append("- ").append(tm == null ? "" : tm.optString("abbreviation") + ": ").append(d.optString("description")).append(" → ").append(d.optString("displayResult", d.optString("result"))).append('\n');
            }
        }
        JSONArray plays = s.optJSONArray("plays");
        if ((prev == null || prev.length() == 0) && plays != null && plays.length() > 0) {
            o.append("Latest plays:\n");
            for (int i = Math.max(0, plays.length() - 15); i < plays.length(); i++) o.append("- ").append(plays.optJSONObject(i).optString("text")).append('\n');
        }
        String f = o.toString();
        return f.length() > 7000 ? f.substring(f.length() - 7000) : f;
    }

    // ================================================================== "For tonight" (a row in Movies and TV Shows)

    /* Claude picks a dozen movies (or shows) for tonight from what you've been watching (TopShelf's own record and
       Trakt's, and your list), each with a few words on why; Cinemeta supplies the posters and ids, so the row
       works like any other. Answered in Cinemeta's shape for the row (Feeds), and kept for six hours. */
    static String tonight(NativeHome h, String type) throws Exception {
        Context c = h.getContext();
        boolean mv = type.equals("movie");
        List<JSONObject> recent = new ArrayList<>();
        JSONObject all = Progress.snapshot(c);
        for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) { JSONObject x = all.optJSONObject(it.next()); if (x != null && x.optJSONObject("title") != null) recent.add(x); }
        java.util.Collections.sort(recent, new java.util.Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(b.optLong("at"), a.optLong("at")); } });
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (JSONObject x : recent) {
            JSONObject t = x.optJSONObject("title");
            String n = t.optString("name");
            if (!n.isEmpty()) seen.add(n + (t.optString("type").equals("movie") ? " (movie)" : " (show)") + (Progress.done(x) ? "" : " — still watching"));
            if (seen.size() >= 40) break;
        }
        try {
            JSONArray ml = new JSONArray(h.prefs.getString("ui.mylist", "[]"));
            for (int i = 0; i < ml.length() && i < 20; i++) { JSONObject m = ml.optJSONObject(i); if (m != null && !m.optString("name").isEmpty()) seen.add(m.optString("name") + " (on their list)"); }
        } catch (Exception ignored) { }
        if (seen.isEmpty()) return "{\"metas\":[]}";
        StringBuilder taste = new StringBuilder();
        for (String s : seen) taste.append("- ").append(s).append('\n');
        String reply = ask(c, "You pick what to watch tonight on a TV. Reply with only JSON: {\"picks\":[{\"title\":\"…\",\"year\":2014,\"why\":\"…\"}]} — 12 "
                + (mv ? "movies" : "TV series") + " this viewer would enjoy, from their recent watching below. Mix close matches with a few they may not know. "
                + "Never pick something in their list below. \"why\" is at most 8 words (for example \"Because you liked Godzilla Minus One\").",
                "Recently watched and saved:\n" + taste, 1200);
        JSONObject o = firstJson(reply);
        JSONArray picks = o == null ? null : o.optJSONArray("picks");
        if (picks == null) throw new Exception("no picks");
        final JSONObject[] got = new JSONObject[picks.length()];
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        for (int i = 0; i < picks.length(); i++) {
            final int k = i; final JSONObject p = picks.optJSONObject(i); final String ty = type;
            if (p == null) continue;
            pool.execute(new Runnable() { @Override public void run() {
                try {
                    byte[] d = HomeImages.http(NativeVod.CINEMETA + "/catalog/" + ty + "/top/search=" + android.net.Uri.encode(p.optString("title")) + ".json", null);
                    JSONArray ms = d == null ? null : new JSONObject(new String(d, StandardCharsets.UTF_8)).optJSONArray("metas");
                    if (ms == null || ms.length() == 0) return;
                    JSONObject best = ms.optJSONObject(0);
                    String y = p.optString("year");
                    for (int j = 0; j < ms.length() && j < 6; j++) { JSONObject m = ms.optJSONObject(j); if (m != null && !y.isEmpty() && m.optString("releaseInfo").startsWith(y)) { best = m; break; } }
                    if (best == null || best.optString("poster").isEmpty()) return;
                    String why = p.optString("why").trim();
                    if (!why.isEmpty()) best.put("description", why + (why.endsWith(".") ? " " : ". ") + best.optString("description"));
                    got[k] = best;
                } catch (Throwable ignored) { }
            } });
        }
        pool.shutdown();
        pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS);
        JSONArray metas = new JSONArray();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JSONObject m : got) if (m != null && ids.add(m.optString("id"))) metas.put(m);
        Diag.ev("assistant: For tonight · " + metas.length() + " " + (mv ? "movies" : "shows"));
        return new JSONObject().put("metas", metas).toString();
    }

    // ================================================================== Claude

    static String ask(Context c, String system, String user, int maxTokens) throws Exception {
        return call(c, MODEL, system, new JSONArray().put(new JSONObject().put("role", "user").put("content", user)), maxTokens, false);
    }
    /* one call to Claude; with `web`, Claude may look things up on the web (Anthropic's own web search, up to three
       searches) — if that's refused (an account without it), asked again without */
    static String call(Context c, String model, String system, JSONArray messages, int maxTokens, boolean web) throws Exception {
        try { return call1(c, model, system, messages, maxTokens, web); }
        catch (Exception e) { if (web && String.valueOf(e.getMessage()).contains("HTTP 400")) return call1(c, model, system, messages, maxTokens, false); throw e; }
    }
    private static String call1(Context c, String model, String system, JSONArray messages, int maxTokens, boolean web) throws Exception {
        String k = key(c);
        if (k.isEmpty()) throw new Exception("no key");
        JSONObject body = new JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", system).put("messages", messages);
        if (web) body.put("tools", new JSONArray().put(new JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3)));
        HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
        try {
            con.setConnectTimeout(8000); con.setReadTimeout(web ? 60000 : 30000);
            con.setRequestMethod("POST"); con.setDoOutput(true);
            con.setRequestProperty("content-type", "application/json");
            con.setRequestProperty("x-api-key", k);
            con.setRequestProperty("anthropic-version", "2023-06-01");
            try (OutputStream os = con.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            if (in != null) while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            if (code >= 400) throw new Exception("HTTP " + code);
            JSONObject r = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            JSONArray content = r.optJSONArray("content");
            StringBuilder t = new StringBuilder();
            if (content != null) for (int i = 0; i < content.length(); i++) { JSONObject p = content.optJSONObject(i); if (p != null && "text".equals(p.optString("type"))) t.append(p.optString("text")); }
            return t.toString();
        } finally { con.disconnect(); }
    }
    private static JSONObject firstJson(String s) {
        if (s == null) return null;
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try { return new JSONObject(s.substring(a, b + 1)); } catch (Exception e) { return null; }
    }

    // ================================================================== the card

    /* A card at the top of the screen with what Claude said: over anything (full-screen TV too), gone after a
       reading time or at the next press of the remote. */
    private static LinearLayout card;
    private static TextView cardTitle, cardBody;
    private static final Runnable HIDE = new Runnable() { @Override public void run() { hideCard(); } };
    static void card(NativeHome h, String title, String text) {
        if (card == null || card.getParent() != h) {
            if (card != null && card.getParent() != null) ((FrameLayout) card.getParent()).removeView(card);
            card = new LinearLayout(h.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable(); bg.setColor(0xF2121217); bg.setCornerRadius(1.6f * h.R); bg.setStroke(Math.max(1, h.px(.1f)), 0x26FFFFFF);
            card.setBackground(bg);
            card.setPadding(h.px(2.4f), h.px(1.8f), h.px(2.4f), h.px(2f));
            cardTitle = h.text(h.fSemi, 1.3f, 0xB3FFFFFF);
            cardBody = h.text(h.fReg, 1.45f, Color.WHITE);
            cardBody.setLineSpacing(0, 1.3f);
            card.addView(cardTitle);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); bl.topMargin = h.px(.7f);
            card.addView(cardBody, bl);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(h.px(62), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = h.px(3);
            h.addView(card, lp);
            card.setVisibility(View.GONE);
        }
        cardTitle.setText(title);
        cardBody.setText(text);
        card.bringToFront();
        if (card.getVisibility() != View.VISIBLE) {
            card.setVisibility(View.VISIBLE);
            card.setAlpha(0f); card.setTranslationY(-h.px(2));
            card.animate().alpha(1f).translationY(0).setDuration(240).start();
        }
        MAIN.removeCallbacks(HIDE);
        int words = text.split("\\s+").length;
        // (while it's still working — "Thinking…" — it stays until the answer replaces it)
        MAIN.postDelayed(HIDE, text.endsWith("…") ? 75000 : Math.max(9000, Math.min(40000, words * 420L + 4000)));
    }
    static boolean cardShown() { return card != null && card.getVisibility() == View.VISIBLE; }
    // ================================================================== Claude's voice (the TV's own text-to-speech)

    /* Answers read aloud (Settings › System › Assistant › Read answers aloud) by the TV's own voice, Android's
       text-to-speech: nothing more is sent anywhere. Any press of the remote stops it. */
    private static android.speech.tts.TextToSpeech tts;
    private static boolean ttsReady;
    private static String ttsWaiting;
    static boolean speaks(Context c) { return prefs(c).getBoolean("aiSpeak", true); }
    static void speak(Context c, final String text) {
        if (text == null || text.trim().isEmpty() || !speaks(c)) return;
        if (tts == null) {
            ttsWaiting = text;
            tts = new android.speech.tts.TextToSpeech(c.getApplicationContext(), new android.speech.tts.TextToSpeech.OnInitListener() { @Override public void onInit(int st) {
                ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS;
                if (!ttsReady) { Diag.ev("assistant: the TV has no voice to read answers with"); return; }
                try { tts.setLanguage(Locale.getDefault()); tts.setSpeechRate(1.05f); } catch (Throwable ignored) { }
                String w = ttsWaiting; ttsWaiting = null;
                if (w != null) tts.speak(w, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "claude");
            } });
            return;
        }
        if (!ttsReady) { ttsWaiting = text; return; }
        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "claude");
    }
    static void hush() { try { if (tts != null && ttsReady) tts.stop(); } catch (Throwable ignored) { } ttsWaiting = null; }

    static void hideCard() {
        hush();
        MAIN.removeCallbacks(HIDE);
        if (!cardShown()) return;
        final LinearLayout c = card;
        c.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() { @Override public void run() { c.setVisibility(View.GONE); } }).start();
    }
}
