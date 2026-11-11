package com.topshelf.launcher;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * The doorbell on screen (Ring, see Ring.java). A small card in the top right: the picture the doorbell took
 * (it arrives a few seconds after the card), with the doorbell's name and the time under it. OK opens the
 * doorbell's live video full screen (Ring's live view: WebRTC, set up the way Ring's own apps do it, in a
 * web view of its own); Back ends it and closes everything. The card goes by itself after a minute; the live
 * video stays until Back (or until Ring ends it, after five minutes at most). What was playing is paused
 * while it's up and carries on after (see NativeHome.doorbell).
 */
final class Doorbell extends FrameLayout {
    private final NativeHome h;
    private final LinearLayout card;
    private final TextView name, when, wait, hint;
    private final ImageView pic, big;
    private final FrameLayout picBox, full, liveHost;
    private final TextView fullLabel, status;
    private final View liveDot;
    private boolean up, isFull;
    private Runnable onClose;
    private String device = "", deviceId = "";

    Doorbell(NativeHome home) {
        super(home.getContext());
        h = home;
        boolean air = GlassPanel.look.equals("air");
        float rad = air ? 0 : 1.1f * h.R;
        setClickable(false);
        // ---- the card: the picture first, one quiet line under it
        card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(h.px(.6f), h.px(.6f), h.px(.6f), h.px(.9f));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xE60B0C10); bg.setCornerRadius(rad + .6f * h.R * (air ? 0 : 1));
        bg.setStroke(Math.max(1, h.px(.08f)), Ui.WHITE_12);
        card.setBackground(bg);
        card.setElevation(h.px(1.5f));
        picBox = new FrameLayout(getContext());
        GradientDrawable pb = new GradientDrawable(); pb.setColor(0xFF15171D); pb.setCornerRadius(rad); picBox.setBackground(pb);
        picBox.setClipToOutline(true);
        pic = new ImageView(getContext()); pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picBox.addView(pic, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        wait = h.text(h.fMed, Ui.TEXT_S, Ui.WHITE_60); wait.setText("Getting the picture…");
        picBox.addView(wait, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        card.addView(picBox, new LinearLayout.LayoutParams(h.px(24), h.px(13.5f)));
        LinearLayout line = new LinearLayout(getContext()); line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(h.px(.5f), 0, h.px(.5f), 0);
        View dot = new View(getContext());
        GradientDrawable dd = new GradientDrawable(); dd.setShape(GradientDrawable.OVAL); dd.setColor(0xFFFF453A); dot.setBackground(dd);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(h.px(.7f), h.px(.7f)); dl.rightMargin = h.px(.7f);
        line.addView(dot, dl);
        name = h.text(h.fSemi, Ui.TEXT_M, Color.WHITE); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(name, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        when = h.text(h.fMed, Ui.TEXT_S, Ui.WHITE_60);
        line.addView(when);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ll.topMargin = h.px(.8f);
        card.addView(line, ll);
        hint = h.text(h.fMed, Ui.TEXT_XS, Ui.WHITE_50);
        hint.setPadding(h.px(.5f), 0, h.px(.5f), 0);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); il.topMargin = h.px(.35f);
        card.addView(hint, il);
        LayoutParams cl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        cl.topMargin = h.px(3); cl.rightMargin = h.px(3);
        addView(card, cl);
        // ---- full screen: the picture, the live video over it once it comes
        full = new FrameLayout(getContext());
        full.setBackgroundColor(0xFF000000);
        big = new ImageView(getContext()); big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        full.addView(big, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        liveHost = new FrameLayout(getContext());
        liveHost.setAlpha(0f);
        full.addView(liveHost, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        View shade = new View(getContext());
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { Ui.BLACK_60, 0x00000000 }));
        full.addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, h.px(10)));
        LinearLayout lab = new LinearLayout(getContext()); lab.setGravity(Gravity.CENTER_VERTICAL);
        liveDot = new View(getContext());
        GradientDrawable ld = new GradientDrawable(); ld.setShape(GradientDrawable.OVAL); ld.setColor(0xFFFF453A); liveDot.setBackground(ld);
        LinearLayout.LayoutParams ldl = new LinearLayout.LayoutParams(h.px(.8f), h.px(.8f)); ldl.rightMargin = h.px(.8f);
        lab.addView(liveDot, ldl);
        fullLabel = h.text(h.fSemi, Ui.TEXT_L, Color.WHITE);
        fullLabel.setShadowLayer(.8f * h.R, 0, .1f * h.R, Ui.BLACK_85);
        lab.addView(fullLabel);
        status = h.text(h.fMed, Ui.TEXT_M, Ui.WHITE_85);
        status.setShadowLayer(.8f * h.R, 0, .1f * h.R, Ui.BLACK_85);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); sl.leftMargin = h.px(1.4f);
        lab.addView(status, sl);
        LayoutParams fl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT); fl.leftMargin = h.px(4); fl.topMargin = h.px(3);
        full.addView(lab, fl);
        TextView fh = h.text(h.fMed, Ui.TEXT_S, Ui.WHITE_75); fh.setText("Back · Close"); fh.setShadowLayer(.8f * h.R, 0, .1f * h.R, Ui.BLACK_85);
        LayoutParams fhl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT); fhl.rightMargin = h.px(4); fhl.bottomMargin = h.px(3);
        full.addView(fh, fhl);
        full.setVisibility(GONE);
        addView(full, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
    }

    boolean shownNow() { return up; }
    private boolean canLive() { return h.act.ring != null && h.act.ring.connected() && !deviceId.isEmpty(); }

    void show(String dev, String devId, Runnable closed) {
        onClose = closed;
        device = dev == null || dev.isEmpty() ? "Doorbell" : dev;
        deviceId = devId == null ? "" : devId;
        String t = Reminders.time(h, System.currentTimeMillis());
        name.setText(device);
        when.setText(t);
        hint.setText(canLive() ? "OK · Live video    Back · Close" : "OK · Full screen    Back · Close");
        if (!isFull) { pic.setImageDrawable(null); big.setImageDrawable(null); wait.setText("Getting the picture…"); wait.setVisibility(VISIBLE); }
        if (!up) { isFull = false; full.setVisibility(GONE); card.setVisibility(VISIBLE); setVisibility(VISIBLE); card.setTranslationX(h.px(8)); card.setAlpha(0f); }
        up = true;
        bringToFront();
        if (!isFull) {
            card.animate().cancel();
            card.animate().translationX(0).alpha(1f).setDuration(Ui.SLIDE).setInterpolator(TileView.EASE).start();
            removeCallbacks(autoHide); postDelayed(autoHide, 60000);
        }
        h.act.haptic();
    }
    void picture(Bitmap b) {
        if (!up) return;
        if (b == null) { wait.setText("No picture from the doorbell"); return; }
        wait.setVisibility(GONE);
        pic.setImageBitmap(b); big.setImageBitmap(b);
        pic.setAlpha(0f); pic.animate().alpha(1f).setDuration(Ui.SLIDE).start();
    }
    void ok() {
        if (!up || isFull) return;
        isFull = true;
        full.setVisibility(VISIBLE);
        card.setVisibility(GONE);
        fullLabel.setText(device);
        removeCallbacks(autoHide);
        if (canLive()) startLive();
        else { liveDot.setVisibility(GONE); status.setText(when.getText()); postDelayed(autoHide, 60000); }
    }
    void back() { hide(); }
    void hide() {
        if (!up) return;
        up = false; isFull = false;
        stopLive();
        removeCallbacks(autoHide);
        full.setVisibility(GONE); card.setVisibility(VISIBLE);
        card.animate().cancel();
        card.animate().translationX(h.px(8)).alpha(0f).setDuration(Ui.BASE).setInterpolator(TileView.EASE).withEndAction(new Runnable() { @Override public void run() {
            if (!up) setVisibility(GONE);
        } }).start();
        Runnable r = onClose; onClose = null;
        if (r != null) r.run();
    }
    private final Runnable autoHide = new Runnable() { @Override public void run() { hide(); } };

    // ================================================================== live video

    private WebView web;
    private int liveTok;
    private boolean livePlaying;
    private final Runnable liveTimeout = new Runnable() { @Override public void run() { if (!livePlaying) liveEnded("no video from the doorbell"); } };
    private final Runnable liveCap = new Runnable() { @Override public void run() { liveEnded("five minutes are up"); } };

    private void startLive() {
        final int my = ++liveTok;
        livePlaying = false;
        liveDot.setVisibility(VISIBLE); liveDot.setAlpha(.35f);
        status.setText("Connecting to the doorbell…");
        Diag.ev("doorbell: live video asked for");
        postDelayed(liveTimeout, 30000);
        new Thread(new Runnable() { @Override public void run() {
            String ticket = null, err = "";
            try { ticket = h.act.ring.liveTicket(); } catch (Throwable t) { err = String.valueOf(t.getMessage()); }
            final String tk = ticket, er = err;
            post(new Runnable() { @Override public void run() {
                if (my != liveTok || !isFull) return;
                if (tk == null || tk.isEmpty()) { liveEnded("Ring didn't open live video" + (er.isEmpty() ? "" : " (" + er + ")")); return; }
                openWeb(tk);
            } });
        } }, "doorbell-live").start();
    }

    @SuppressLint({ "SetJavaScriptEnabled", "AddJavascriptInterface" })
    private void openWeb(String ticket) {
        final int my = liveTok;
        web = new WebView(h.act);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        web.setFocusable(false);
        web.setWebChromeClient(new WebChromeClient() {
            int n;
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR && n++ < 4) Diag.ev("doorbell live · " + String.valueOf(m.message()).replaceAll("https?://\\S+|wss?://\\S+", "…"));
                return true;
            }
        });
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void ev(final String m) { Diag.ev("doorbell live: " + m); }
            @JavascriptInterface public void playing() { post(new Runnable() { @Override public void run() { if (my == liveTok) livePlays(); } }); }
            @JavascriptInterface public void ended(final String why) { post(new Runnable() { @Override public void run() { if (my == liveTok) liveEnded(why); } }); }
        }, "RL");
        liveHost.addView(web, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        String page = PAGE.replace("%TICKET%", JSONObject.quote(ticket)).replace("%ID%", JSONObject.quote(deviceId));
        web.loadDataWithBaseURL(MainActivity.ORIGIN, page, "text/html", "utf-8", null);
    }
    private void livePlays() {
        if (!isFull) return;
        livePlaying = true;
        removeCallbacks(liveTimeout);
        removeCallbacks(liveCap); postDelayed(liveCap, 5 * 60000);
        status.setText("Live");
        liveDot.setAlpha(1f);
        liveHost.animate().cancel(); liveHost.animate().alpha(1f).setDuration(Ui.SLIDE).start();
        Diag.ev("doorbell: live video playing");
    }
    private void liveEnded(String why) {
        boolean was = livePlaying;
        closeWeb(true);
        if (!isFull) return;
        Diag.ev("doorbell: live video " + (was ? "ended" : "didn't start") + " (" + why + ")");
        liveDot.setAlpha(.35f);
        status.setText(was ? "Live video ended" : "Live video didn't start · " + why);
        removeCallbacks(autoHide); postDelayed(autoHide, 60000);
    }
    private void stopLive() { liveTok++; closeWeb(true); }
    private void closeWeb(boolean say) {
        removeCallbacks(liveTimeout); removeCallbacks(liveCap);
        livePlaying = false;
        liveHost.animate().cancel(); liveHost.setAlpha(0f);
        final WebView w = web; web = null;
        if (w == null) return;
        try { if (say) w.evaluateJavascript("window.stopLive&&stopLive()", null); } catch (Throwable ignored) { }
        // (a moment for the goodbye to reach Ring, then the page goes)
        postDelayed(new Runnable() { @Override public void run() {
            try { liveHost.removeView(w); w.loadUrl("about:blank"); w.destroy(); } catch (Throwable ignored) { }
        } }, 600);
    }

    /* Ring's live view, as ring-client-api does it: a one-time ticket opens Ring's signalling socket; TopShelf
       offers to receive video (and sound), Ring answers, they swap network routes, the session is activated,
       and a ping every five seconds keeps it open. "end_session" closes it. */
    private static final String PAGE = "<!doctype html><html><head><meta name=viewport content='width=device-width'>"
        + "<style>html,body{margin:0;height:100%;background:transparent;overflow:hidden}video{width:100%;height:100%;object-fit:contain;background:#000}</style></head>"
        + "<body><video id=v autoplay playsinline></video><script>\n"
        + "const TICKET=%TICKET%, ID=+%ID%;\n"
        + "const dialog=(crypto.randomUUID?crypto.randomUUID():String(Math.random()).slice(2)+Date.now());\n"
        + "let ws, pc, session=null, offerSent=false, queue=[], ping=null, done=false;\n"
        + "const v=document.getElementById('v');\n"
        + "function send(m){ if(ws&&ws.readyState===1) ws.send(JSON.stringify(m)); }\n"
        + "function sess(method,extra){ send({method, dialog_id:dialog, body:Object.assign({doorbot_id:ID, session_id:session}, extra||{})}); }\n"
        + "function bye(){ clearInterval(ping); try{ send({reason:{code:0,text:''}, method:'close'}); }catch(e){} setTimeout(()=>{ try{ws.close()}catch(e){} try{pc.close()}catch(e){} },300); }\n"
        + "function end(why){ if(done) return; done=true; bye(); RL.ended(why); }\n"
        + "window.stopLive=()=>{ if(!done){ done=true; bye(); } };\n"
        + "try {\n"
        + " pc=new RTCPeerConnection({iceServers:['stun:stun.kinesisvideo.us-east-1.amazonaws.com:443','stun:stun.kinesisvideo.us-east-2.amazonaws.com:443','stun:stun.kinesisvideo.us-west-2.amazonaws.com:443','stun:stun.l.google.com:19302','stun:stun1.l.google.com:19302'].map(u=>({urls:u}))});\n"
        + " pc.addTransceiver('audio',{direction:'sendrecv'}); pc.addTransceiver('video',{direction:'recvonly'});\n"
        + " pc.ontrack=e=>{ const s=e.streams&&e.streams[0]?e.streams[0]:new MediaStream([e.track]); if(v.srcObject!==s) v.srcObject=s; v.play().catch(()=>{ v.muted=true; v.play().catch(()=>{}); RL.ev('sound blocked, playing muted'); }); };\n"
        + " v.addEventListener('playing',()=>RL.playing());\n"
        + " pc.onicecandidate=e=>{ if(!e.candidate) return; const m={method:'ice', dialog_id:dialog, body:{doorbot_id:ID, ice:e.candidate.candidate, mlineindex:e.candidate.sdpMLineIndex}}; if(offerSent) send(m); else queue.push(m); };\n"
        + " pc.onconnectionstatechange=()=>{ if(pc.connectionState==='failed') end('the connection failed'); };\n"
        + " ws=new WebSocket('wss://api.prod.signalling.ring.devices.a2z.com:443/ws?api_version=4.0&auth_type=ring_solutions&client_id=ring_site-'+dialog+'&token='+encodeURIComponent(TICKET));\n"
        + " ws.onopen=async()=>{ RL.ev('signalling open'); const o=await pc.createOffer(); await pc.setLocalDescription(o);\n"
        + "   send({method:'live_view', dialog_id:dialog, body:{doorbot_id:ID, stream_options:{audio_enabled:true, video_enabled:true}, sdp:o.sdp}}); offerSent=true; queue.forEach(send); queue=[]; };\n"
        + " ws.onmessage=async e=>{ let m; try{ m=JSON.parse(e.data); }catch(x){ return; } const b=m.body||{};\n"
        + "   if(b.doorbot_id!==undefined && +b.doorbot_id!==ID) return;\n"
        + "   if((m.method==='session_created'||m.method==='session_started') && b.session_id && !session){ session=b.session_id; RL.ev('session '+m.method.slice(8)); clearInterval(ping); ping=setInterval(()=>sess('ping'),5000); }\n"
        + "   if(b.session_id && session && b.session_id!==session) return;\n"
        + "   if(m.method==='session_created'||m.method==='session_started'||m.method==='camera_started'||m.method==='stream_info'){ }\n"
        + "   else if(m.method==='ping'){ sess('pong'); }\n"
        + "   else if(m.method==='sdp'){ await pc.setRemoteDescription({type:'answer', sdp:b.sdp}); RL.ev('answered'); sess('activate_session'); sess('stream_options',{audio_enabled:true, video_enabled:true}); }\n"
        + "   else if(m.method==='ice'){ try{ await pc.addIceCandidate({candidate:b.ice, sdpMLineIndex:b.mlineindex}); }catch(x){} }\n"
        + "   else if(m.method==='close'){ end('Ring closed it' + (b.reason&&b.reason.text?' · '+b.reason.text:'')); }\n"
        + "   else if(m.method==='notification'){ const t=String(b.text||''); if(t.indexOf('ConnectionFailed')>=0) end('the connection failed'); else if(t==='camera_connected') RL.ev('camera connected'); }\n"
        + "   else if(m.method!=='pong'){ RL.ev('message '+m.method); } };\n"
        + " ws.onerror=()=>RL.ev('signalling error');\n"
        + " ws.onclose=e=>{ if(!done) end('the signalling closed (' + e.code + ')'); };\n"
        + "} catch(e) { end('this TV\\'s web view can\\'t do live video (' + e.message + ')'); }\n"
        + "</script></body></html>";
}
