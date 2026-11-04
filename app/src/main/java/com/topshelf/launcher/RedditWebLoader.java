package com.topshelf.launcher;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens Reddit in a hidden, real browser (a WebView behind the home screen) and reads the
 * posts off the page. Reddit refuses plain app requests, but a normal browser visit works.
 * Tries the JSON listing, then old.reddit's page, then the new reddit page.
 * Must be created on the main thread.
 */
final class RedditWebLoader {

    interface Callback { void done(String json, String error); }

    private static final String EXTRACT_JS =
        "(function(){try{" +
        "var t=document.body?document.body.innerText.trim():'';" +
        "var pre=document.querySelector('pre');var pt=pre?pre.textContent.trim():'';" +
        "if(pt.charAt(0)==='{')return JSON.stringify({kind:'json',body:pt});" +
        "if(t.charAt(0)==='{')return JSON.stringify({kind:'json',body:t});" +
        "var out=[],seen={};" +
        "function size(s){var m=/(\\d{3,5})\\s*[x\\u00d7X]\\s*(\\d{3,5})/.exec(s||'');return m?[+m[1],+m[2]]:[0,0];}" +
        "function isImg(u){return /\\.(jpe?g|png|webp)(\\?|$)/i.test(u||'');}" +
        // old.reddit.com listing
        "var th=document.querySelectorAll('div.thing[data-url]');" +
        "for(var i=0;i<th.length;i++){var d=th[i];" +
        " if(d.getAttribute('data-nsfw')==='true'||d.classList.contains('stickied'))continue;" +
        " var u=d.getAttribute('data-url');if(!isImg(u)||seen[u])continue;seen[u]=1;" +
        " var a=d.querySelector('a.title');var ti=a?a.textContent:'';var wh=size(ti);" +
        " out.push({img:u,title:ti,author:d.getAttribute('data-author')||'',sub:d.getAttribute('data-subreddit')||''," +
        "  link:'https://reddit.com'+(d.getAttribute('data-permalink')||''),w:wh[0],h:wh[1]});}" +
        // new reddit (shreddit) listing
        "var ps=document.querySelectorAll('shreddit-post');" +
        "for(var j=0;j<ps.length;j++){var p=ps[j];" +
        " if(p.hasAttribute('nsfw')&&p.getAttribute('nsfw')!=='false')continue;" +
        " var ti2=p.getAttribute('post-title')||'';var href=p.getAttribute('content-href')||'';" +
        " var best=null,bw=0,big=null,bigw=0;var ims=p.querySelectorAll('img[srcset]');" +
        " for(var k=0;k<ims.length;k++){var parts=(ims[k].getAttribute('srcset')||'').split(/,\\s+/);" +
        "  for(var q=0;q<parts.length;q++){var bits=parts[q].trim().split(/\\s+/);var w=parseInt(bits[1]||'0',10);var url=bits[0];" +
        "   if(!url||url.indexOf('redd.it')<0)continue;" +
        "   if(w>=1920&&(!best||w<bw)){best=url;bw=w;} if(w>bigw){big=url;bigw=w;}}}" +
        " var img=best||(isImg(href)?href:big);if(!img||seen[img])continue;seen[img]=1;var wh2=size(ti2);" +
        " var sp=(p.getAttribute('subreddit-prefixed-name')||'').replace(/^r\\//,'');" +
        " out.push({img:img,full:(isImg(href)&&href!==img)?href:null,title:ti2,author:p.getAttribute('author')||'',sub:sp," +
        "  link:'https://reddit.com'+(p.getAttribute('permalink')||''),w:wh2[0],h:wh2[1]});}" +
        "return JSON.stringify({kind:'items',items:out,info:(document.title||'no title')+' | '+t.slice(0,60).replace(/\\s+/g,' ')});" +
        "}catch(e){return JSON.stringify({kind:'items',items:[],info:'script error '+e});}})()";

    private final MainActivity act;
    private final Callback cb;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<String[]> pages = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private WebView wv;
    private int index = -1, polls = 0, httpStatus = 0;
    private boolean finished;
    private JSONArray best;                       // the most posts seen so far on this page

    @SuppressLint("SetJavaScriptEnabled")
    RedditWebLoader(MainActivity act, String sub, String sort, Callback cb) {
        this.act = act;
        this.cb = cb;
        sub = sub.replaceAll("[^A-Za-z0-9_+]", "");
        String t = "", listing;
        if (sort.startsWith("top-")) { listing = "top"; t = sort.substring(4); }
        else if (sort.equals("new")) listing = "new";
        else listing = "hot";
        String q = t.isEmpty() ? "" : "t=" + t;
        pages.add(new String[]{"page-json", "https://www.reddit.com/r/" + sub + "/" + listing + ".json?limit=100&raw_json=1" + (q.isEmpty() ? "" : "&" + q)});
        pages.add(new String[]{"page-old", "https://old.reddit.com/r/" + sub + "/" + listing + "/?limit=100" + (q.isEmpty() ? "" : "&" + q)});
        pages.add(new String[]{"page-new", "https://www.reddit.com/r/" + sub + "/" + listing + "/" + (q.isEmpty() ? "" : "?" + q)});

        wv = new WebView(act);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(false);   // we only need the links, not the pictures
        s.setBlockNetworkImage(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (view == wv && !finished) poll(1500);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
                if (req.isForMainFrame()) httpStatus = resp.getStatusCode();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                errors.add("browser crashed");
                finish(null);
                return true;
            }
        });
        act.addHiddenView(wv);
        h.postDelayed(timeout, 40_000);
        next();
    }

    private final Runnable timeout = new Runnable() {
        @Override public void run() {
            if (finished) return;
            errors.add((index >= 0 && index < pages.size() ? pages.get(index)[0] : "page") + ": timed out");
            finish(null);
        }
    };

    private final Runnable pollRun = new Runnable() {
        @Override public void run() {
            if (finished || wv == null) return;
            wv.evaluateJavascript(EXTRACT_JS, new ValueCallback<String>() {
                @Override public void onReceiveValue(String value) { handle(value); }
            });
        }
    };

    private void next() {
        index++;
        if (index >= pages.size()) { finish(null); return; }
        polls = 0;
        httpStatus = 0;
        best = null;
        wv.loadUrl(pages.get(index)[1]);
    }

    private void poll(long delay) {
        h.removeCallbacks(pollRun);
        h.postDelayed(pollRun, delay);
    }

    private void handle(String value) {
        if (finished) return;
        String label = pages.get(index)[0];
        try {
            Object decoded = new JSONTokener(value).nextValue();   // the JS result arrives JSON-encoded
            JSONObject r = new JSONObject(decoded.toString());
            if ("json".equals(r.optString("kind"))) {
                finish(Reddit.parse(r.getString("body")));
                return;
            }
            JSONArray items = r.optJSONArray("items");
            /* Reddit's new page shows its posts a few at a time as it scrolls; reading it the moment
               it loaded gave just one photo. Scroll and read again until the count stops growing. */
            if (items != null && items.length() > 0 && items.length() < 25 && polls < 8
                    && (best == null || items.length() > best.length())) {
                best = items;
                polls++;
                wv.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight)", null);
                poll(1500);
                return;
            }
            if (best != null && (items == null || items.length() < best.length())) items = best;
            if (items != null && items.length() > 0) {
                JSONArray landscape = new JSONArray(), any = new JSONArray();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    if (o.isNull("full")) o.remove("full");
                    any.put(o);
                    int w = o.optInt("w"), hh = o.optInt("h");
                    if (w == 0 || w >= hh * 1.15) landscape.put(o);
                }
                finish(Reddit.pick(landscape, any));
                return;
            }
            // The page may still be rendering (or passing a browser check): look again a few times.
            if (++polls < 5) { poll(1800); return; }
            errors.add(label + ": " + (httpStatus != 0 ? "HTTP " + httpStatus + ", " : "") + r.optString("info", "no posts"));
        } catch (Exception e) {
            errors.add(label + ": " + e.getMessage());
        }
        next();
    }

    private void finish(String json) {
        if (finished) return;
        finished = true;
        h.removeCallbacks(pollRun);
        h.removeCallbacks(timeout);
        final WebView dead = wv;
        wv = null;
        if (dead != null) act.removeHiddenView(dead);
        cb.done(json, json == null ? String.join("; ", errors) : null);
    }
}
