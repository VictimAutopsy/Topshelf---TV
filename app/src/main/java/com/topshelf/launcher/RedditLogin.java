package com.topshelf.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Signing in to Reddit on the TV: Reddit's own sign-in page, full screen, worked with the remote (▲▼ move
 * between the boxes and buttons, OK types or presses). The sign-in stays in TopShelf's browser cookies, so
 * the hidden browser and the direct requests read Reddit as you (Reddit still serves signed-in visitors).
 * TopShelf never sees or keeps your password: you type it into Reddit's page.
 */
final class RedditLogin extends FrameLayout {
    static final String LOGIN = "https://www.reddit.com/login/";

    /** signed in: Reddit's session cookie is there */
    static boolean signedIn() {
        try { String c = CookieManager.getInstance().getCookie("https://www.reddit.com"); return c != null && c.contains("reddit_session="); }
        catch (Throwable t) { return false; }
    }
    /** for the direct requests */
    static String cookies() {
        try { return CookieManager.getInstance().getCookie("https://www.reddit.com"); } catch (Throwable t) { return null; }
    }
    /** forgets Reddit's cookies (the rest of TopShelf's are kept) */
    static void signOut() {
        try {
            CookieManager cm = CookieManager.getInstance();
            for (String host : new String[] { "https://www.reddit.com", "https://reddit.com", "https://old.reddit.com" }) {
                String c = cm.getCookie(host);
                if (c == null) continue;
                for (String part : c.split(";")) {
                    String name = part.split("=", 2)[0].trim();
                    if (name.isEmpty()) continue;
                    for (String dom : new String[] { "", "; Domain=.reddit.com", "; Domain=reddit.com", "; Domain=www.reddit.com" })
                        cm.setCookie(host, name + "=; Max-Age=0; Path=/" + dom);
                }
            }
            cm.flush();
        } catch (Throwable ignored) { }
    }

    /* moving between the page's boxes and buttons with the remote (Reddit's page is built from web
       components, so the search looks inside them too) */
    private static final String NAV_JS =
        "(function(){if(window.__tsNav)return;" +
        "function all(root,out){var q=root.querySelectorAll('input:not([type=hidden]),button,a[href],[role=button],select,textarea');" +
        " for(var i=0;i<q.length;i++)out.push(q[i]);" +
        " var e=root.querySelectorAll('*');for(var j=0;j<e.length;j++)if(e[j].shadowRoot)all(e[j].shadowRoot,out);return out;}" +
        "function shown(el){var r=el.getBoundingClientRect();if(r.width<4||r.height<4)return false;var s=getComputedStyle(el);return s.visibility!=='hidden'&&s.display!=='none'&&!el.disabled;}" +
        "function deep(){var a=document.activeElement;while(a&&a.shadowRoot&&a.shadowRoot.activeElement)a=a.shadowRoot.activeElement;return a;}" +
        "var cur=null;" +
        "function mark(el){if(cur&&cur!==el){cur.style.outline=cur.__o||'';}cur=el;if(!el)return;if(el.__o===undefined)el.__o=el.style.outline||'';" +
        " el.style.outline='4px solid #0A84FF';el.style.outlineOffset='2px';el.scrollIntoView({block:'center'});el.focus();}" +
        "window.__tsNav=function(d){var l=all(document,[]).filter(shown);if(!l.length)return 'none';" +
        " l.sort(function(a,b){var x=a.getBoundingClientRect(),y=b.getBoundingClientRect();return (x.top-y.top)||(x.left-y.left);});" +
        " var c=cur&&l.indexOf(cur)>=0?cur:deep();var i=l.indexOf(c);" +
        " if(d==='ok'){if(!c||i<0){mark(l[0]);return 'moved';}var t=(c.tagName||'').toLowerCase();" +
        "  if(t==='input'&&!/^(checkbox|radio|submit|button)$/i.test(c.type)||t==='textarea'){c.focus();return 'type';}c.click();return 'clicked';}" +
        " var n=i<0?0:d==='down'?Math.min(l.length-1,i+1):Math.max(0,i-1);mark(l[n]);" +
        " var t2=(l[n].tagName||'').toLowerCase();return (t2==='input'&&!/^(checkbox|radio|submit|button)$/i.test(l[n].type))||t2==='textarea'?'field':'moved';};})()";

    interface Done { void done(boolean signedIn); }

    private final WebView wv;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Done done;
    private boolean closed;

    @SuppressLint("SetJavaScriptEnabled")
    RedditLogin(Context c, Done done) {
        super(c);
        this.done = done;
        setBackgroundColor(0xFF0B0C10);
        wv = new WebView(c);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail d) {
                if (view.getParent() != null) ((android.view.ViewGroup) view.getParent()).removeView(view);
                try { view.destroy(); } catch (Throwable ignored) { }
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) { view.evaluateJavascript(NAV_JS, null); }
        });
        int top = dp(56);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        lp.topMargin = top;
        addView(wv, lp);
        TextView bar = new TextView(c);
        bar.setText("Sign in to Reddit   ·   ▲ ▼ move   ·   OK type or press   ·   Back close   (TopShelf doesn't see your password)");
        bar.setTextColor(Color.WHITE);
        bar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(24), 0, dp(24), 0);
        GradientDrawable g = new GradientDrawable(); g.setColor(0xFF1C1D24);
        bar.setBackground(g);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, top));
        wv.loadUrl(LOGIN);
        main.postDelayed(watch, 1500);
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** signed in: say so and close */
    private final Runnable watch = new Runnable() { @Override public void run() {
        if (closed) return;
        if (signedIn()) { try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { } close(true); return; }
        main.postDelayed(this, 1000);
    } };

    void close(boolean ok) {
        if (closed) return;
        closed = true;
        main.removeCallbacks(watch);
        try { ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(wv.getWindowToken(), 0); } catch (Throwable ignored) { }
        if (getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) getParent()).removeView(this);
        wv.destroy();
        done.done(ok);
    }

    /** the remote while it's open (true: handled) */
    boolean key(KeyEvent e) {
        int c = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (c == KeyEvent.KEYCODE_BACK) {
            if (!down && !e.isCanceled()) close(signedIn());
            return true;
        }
        // while typing, the keyboard has the keys
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && imm.isAcceptingText() && imm.isActive(wv) && typing) {
            if (c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_DPAD_DOWN) typing = false;
            else return false;
        }
        String d = c == KeyEvent.KEYCODE_DPAD_DOWN ? "down" : c == KeyEvent.KEYCODE_DPAD_UP ? "up"
                : c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER ? "ok" : null;
        if (d == null) return false;
        if (!down) return true;
        wv.evaluateJavascript(NAV_JS + ";window.__tsNav('" + d + "')", new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String r) {
                if (r != null && r.contains("type")) {
                    typing = true;
                    wv.requestFocus();
                    InputMethodManager m = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (m != null) m.showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
        return true;
    }
    private boolean typing;
}
