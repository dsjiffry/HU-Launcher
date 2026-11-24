package com.hu.launcher;

import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny HTTP server for the PC link. Runs only while the PC link is on. Every call except
 * "/", /ping and /stream (one-time token) needs the 6-digit PIN in the X-Pin header, checked
 * before any request body is read. Wrong PINs: 5 free tries per IP, then bans of 30 s doubling
 * to 1 h; 200 wrong PINs in total stop the link. Requests without a PIN and CORS preflights are
 * refused without counting.
 * Advertised over mDNS/DNS-SD as _hulauncher._tcp.
 *
 *   GET  /                 remote-control web page (open it in a PC browser)
 *   GET  /ping             {"app":"hulauncher",...}
 *   GET  /apps             device info + every package + current verdicts
 *   GET  /verdicts         POST /verdicts {"replace":false,"verdicts":{"pkg":{"v":"bloat","why":".."}}}
 *   POST /remote/start     asks the unit to start screen capture   GET /remote/status   POST /remote/stop
 *   GET  /screen.jpg       one frame          GET /stream   MJPEG stream
 *   POST /input            {"type":"tap|long|swipe|back|home|recents|notifications|text|launch", x,y,x2,y2 (0..1), ms, text, pkg}
 *   GET  /net              firewall state      POST /net {"enabled":true,"allow":[..],"block":[..]}
 *   GET  /stats            device RAM/CPU/temperature + per-app usage
 *   GET  /keep             "keep stopped" list   POST /keep {"add":[..],"remove":[..]}
 *   GET  /install          last install result + this app's version
 *   POST /install          raw APK body (Content-Type application/vnd.android.package-archive, ?name=)
 *                          or {"bluetooth":true} (newest APK received over Bluetooth) or {"path":".."}
 */
final class LanServer {
    interface Events { void onVerdicts(int count); void onStopped(String reason); }

    static final int PORT = 8765;
    private static final int MAX_BODY = 2 * 1024 * 1024;

    final String pin;
    private final Context ctx;
    private final Verdicts verdicts;
    private final Events events;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Semaphore slots = new Semaphore(16);
    // Security limits (see the PC-link security review):
    private static final int MAX_PER_IP = 6, MAX_STREAMS = 3, FREE_TRIES = 5, GLOBAL_BAD_LIMIT = 200;
    private static final long REQUEST_DEADLINE_MS = 10000, TOKEN_TTL_MS = 60000;
    private final Map<String, Integer> perIp = new HashMap<>();          // open connections per IP
    private final Map<String, long[]> failures = new HashMap<>();        // ip -> {count, bannedUntil}
    private final Map<String, Long> streamTokens = new HashMap<>();      // one-time stream tokens
    private final AtomicInteger totalBad = new AtomicInteger();
    private final AtomicInteger streams = new AtomicInteger();
    private final SecureRandom random = new SecureRandom();
    private ServerSocket server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener reg;
    private volatile boolean running;

    LanServer(Context c, Verdicts v, Events events, String pin) {
        this.ctx = c.getApplicationContext();
        this.verdicts = v;
        this.events = events;
        this.pin = pin;
    }

    static String newPin() { return String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000)); }

    int start() throws IOException {
        try { server = new ServerSocket(PORT); } catch (IOException e) { server = new ServerSocket(0); }
        running = true;
        Thread t = new Thread(this::loop, "lan-accept");
        t.setDaemon(true);
        t.start();
        advertise(server.getLocalPort());
        return server.getLocalPort();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
        if (nsd != null && reg != null) {
            try { nsd.unregisterService(reg); } catch (RuntimeException ignored) { }
        }
        reg = null;
    }

    private void advertise(int port) {
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("HU Launcher"); // no model name: nothing to fingerprint
            info.setServiceType("_hulauncher._tcp");
            info.setPort(port);
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            reg = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo s, int err) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int err) { }
                @Override public void onServiceRegistered(NsdServiceInfo s) { }
                @Override public void onServiceUnregistered(NsdServiceInfo s) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg);
        } catch (RuntimeException ignored) {
            reg = null; // mDNS is a convenience; the PC script can also scan the subnet.
        }
    }

    private void loop() {
        while (running) {
            final Socket s;
            try { s = server.accept(); } catch (IOException e) { if (!running) return; continue; }
            final InetAddress peer = s.getInetAddress();
            // Only local-network peers (Wi-Fi/Ethernet LAN, loopback for adb forward).
            if (!isLanPeer(peer)) { close(s); continue; }
            final String ip = peer.getHostAddress();
            synchronized (perIp) {
                Integer n = perIp.get(ip);
                if (n != null && n >= MAX_PER_IP) { close(s); continue; } // one host can't hog every slot
                perIp.put(ip, n == null ? 1 : n + 1);
            }
            if (!slots.tryAcquire()) { close(s); release(ip); continue; }
            try {
                pool.execute(() -> {
                    try {
                        s.setSoTimeout(5000);
                        handle(s, ip);
                    } catch (Throwable ignored) { // incl. Errors: one bad request must never kill the launcher
                    } finally {
                        close(s);
                        slots.release();
                        release(ip);
                    }
                });
            } catch (RuntimeException rejected) {
                close(s);
                slots.release();
                release(ip);
            }
        }
    }

    private void release(String ip) {
        synchronized (perIp) {
            Integer n = perIp.get(ip);
            if (n == null || n <= 1) perIp.remove(ip); else perIp.put(ip, n - 1);
        }
    }

    static boolean isLanPeer(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 16 && (b[0] & 0xFE) == 0xFC) return true;            // IPv6 ULA fc00::/7
        if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) return true; // CGNAT hotspots
        return false;
    }

    /** Host header must name this unit (blocks DNS-rebinding and drive-by pages using a hostname). */
    private static boolean hostAllowed(String host) {
        if (host == null) return false;
        String h = host.trim();
        if (h.startsWith("[")) h = h.substring(1, Math.max(1, h.indexOf(']')));
        else if (h.indexOf(':') >= 0) h = h.substring(0, h.indexOf(':'));
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")) return true;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    String ah = a.getHostAddress();
                    int pct = ah.indexOf('%');
                    if (pct >= 0) ah = ah.substring(0, pct);
                    if (ah.equalsIgnoreCase(h)) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** Per-IP backoff: 5 free tries, then 30 s, 60 s, 120 s... (max 1 h) bans. Never stops the server. */
    private boolean banned(String ip) {
        synchronized (failures) {
            long[] f = failures.get(ip);
            return f != null && f[1] > System.currentTimeMillis();
        }
    }

    private void recordFailure(String ip) {
        synchronized (failures) {
            long[] f = failures.get(ip);
            if (f == null) { f = new long[2]; failures.put(ip, f); }
            f[0]++;
            if (f[0] >= FREE_TRIES) {
                long ban = Math.min(3600000L, 30000L << Math.min(7, f[0] - FREE_TRIES));
                f[1] = System.currentTimeMillis() + ban;
            }
        }
        // Many attackers / many IPs: after 200 total wrong PINs (0.02% guess odds) shut down.
        if (totalBad.incrementAndGet() >= GLOBAL_BAD_LIMIT) {
            running = false;
            events.onStopped("PC link stopped: too many wrong PINs from the network");
        }
    }

    private void recordSuccess(String ip) {
        synchronized (failures) { failures.remove(ip); }
    }

    private boolean pinMatches(String given) {
        if (given == null) return false;
        return java.security.MessageDigest.isEqual(pin.getBytes(StandardCharsets.US_ASCII),
                given.getBytes(StandardCharsets.US_ASCII));
    }

    private String newStreamToken() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        String t = sb.toString();
        synchronized (streamTokens) {
            long now = System.currentTimeMillis();
            streamTokens.values().removeIf(exp -> exp < now);
            streamTokens.put(t, now + TOKEN_TTL_MS);
        }
        return t;
    }

    /** One-time token, so the real PIN never lands in a URL (browser history, logs). */
    private boolean takeStreamToken(String t) {
        if (t == null) return false;
        synchronized (streamTokens) {
            Long exp = streamTokens.remove(t);
            return exp != null && exp >= System.currentTimeMillis();
        }
    }

    /** Wraps the socket stream with an absolute deadline: trickling bytes can't hold a slot. */
    private static final class Deadline extends java.io.FilterInputStream {
        private volatile long end;
        Deadline(InputStream in, long ms) { super(in); end = System.currentTimeMillis() + ms; }
        void extend(long ms) { end = System.currentTimeMillis() + ms; } // only after the PIN checked out
        private void check() throws IOException {
            if (System.currentTimeMillis() > end) throw new IOException("request too slow");
        }
        @Override public int read() throws IOException { check(); return super.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { check(); return super.read(b, off, len); }
    }

    private static void close(Socket s) { try { s.close(); } catch (IOException ignored) { } }

    // ---- Request handling --------------------------------------------------------------------

    private void handle(Socket s, String ip) throws Exception {
        Deadline deadline = new Deadline(s.getInputStream(), REQUEST_DEADLINE_MS);
        InputStream in = new BufferedInputStream(deadline);
        String requestLine = readLine(in);
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) { send(s, 400, "{\"error\":\"bad request\"}"); return; }
        String method = parts[0], path = parts[1];
        Map<String, String> query = new HashMap<>();
        int q = path.indexOf('?');
        if (q >= 0) {
            for (String kv : path.substring(q + 1).split("&")) {
                int e = kv.indexOf('=');
                if (e > 0) query.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            }
            path = path.substring(0, q);
        }
        long length = 0;
        int headers = 0;
        String givenPin = null, host = null, type = ""; // PIN only via header: never in URLs
        for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
            if (++headers > 64) { send(s, 431, "{\"error\":\"too many headers\"}"); return; }
            int c = h.indexOf(':');
            if (c < 0) continue;
            String name = h.substring(0, c).trim().toLowerCase(Locale.US), val = h.substring(c + 1).trim();
            if (name.equals("content-length")) {
                try { length = Long.parseLong(val); } catch (NumberFormatException e) { length = -1; }
            } else if (name.equals("x-pin")) {
                givenPin = val;
            } else if (name.equals("host")) {
                host = val;
            } else if (name.equals("content-type")) {
                type = val.toLowerCase(Locale.US);
            }
        }
        if (!hostAllowed(host)) { send(s, 403, "{\"error\":\"use this unit's IP address\"}"); return; }
        if (banned(ip)) { send(s, 429, "{\"error\":\"too many wrong PINs - wait and try again\"}"); return; }
        // Cross-origin preflight from some web page: refuse, and don't count it as a PIN guess.
        if (method.equals("OPTIONS")) { send(s, 403, "{\"error\":\"cross-origin requests are not allowed\"}"); return; }
        boolean open = path.equals("/") || path.equals("/index.html") || path.equals("/ping") || path.equals("/stream");
        if (!open) {
            // PIN before reading any body: unauthenticated clients can't make us allocate anything.
            // No PIN at all isn't a guess (e.g. a web page poking the port) - don't count it.
            if (givenPin == null || givenPin.isEmpty()) { send(s, 401, "{\"error\":\"PIN required (X-Pin header)\"}"); return; }
            if (!pinMatches(givenPin)) { recordFailure(ip); send(s, 401, "{\"error\":\"wrong PIN\"}"); return; }
            recordSuccess(ip);
        }
        // APK upload: streamed straight to the installer; only now (PIN checked) the deadline grows.
        if (path.equals("/install") && method.equals("POST") && type.startsWith("application/vnd.android.package-archive")) {
            if (length <= 0 || length > Installer.MAX_APK) { send(s, 413, "{\"error\":\"APK missing or too large\"}"); return; }
            deadline.extend(180000);
            String name = query.containsKey("name") ? query.get("name") : "uploaded.apk";
            int id = Installer.install(ctx, in, length, name);
            send(s, 200, new JSONObject().put("ok", true).put("session", id)
                    .put("helper", AutoClicker.available()).put("message", "sent to the installer").toString());
            return;
        }
        // Read-only copy of a *preinstalled* app's APK (/system, /vendor...), so the PC can see what
        // a vendor app does before you disable it. Never user-installed apps or any data files.
        if (path.equals("/apk") && method.equals("GET")) {
            deadline.extend(120000);
            sendSystemApk(s, query.get("pkg"));
            return;
        }
        if (length < 0 || length > MAX_BODY || (open && length > 0)) { send(s, 413, "{\"error\":\"too large\"}"); return; }
        byte[] body = new byte[(int) length];
        for (int off = 0; off < length; ) {
            int r = in.read(body, off, body.length - off);
            if (r < 0) break;
            off += r;
        }

        if (path.equals("/") || path.equals("/index.html")) { sendPage(s); return; }
        if (path.equals("/ping")) { // just enough for the PC script to recognise the unit
            send(s, 200, "{\"app\":\"hulauncher\"}");
            return;
        }
        if (path.equals("/stream")) { // <img> can't send headers: one-time token
            if (!ScreenCapture.running()) { send(s, 409, "{\"error\":\"screen capture is not running\"}"); return; } // keep the token
            if (method.equals("GET") && takeStreamToken(query.get("t"))) { stream(s); return; }
            recordFailure(ip);
            send(s, 401, "{\"error\":\"invalid or expired stream token\"}");
            return;
        }
        if (path.equals("/stream/token")) {
            send(s, 200, new JSONObject().put("token", newStreamToken()).toString());
            return;
        }
        if (tooDeep(body)) { send(s, 400, "{\"error\":\"JSON nested too deeply\"}"); return; }
        try {
            route(s, method, path, body.length == 0 ? new JSONObject()
                    : new JSONObject(new String(body, StandardCharsets.UTF_8)));
        } catch (Exception | StackOverflowError e) {
            send(s, 500, "{\"error\":" + JSONObject.quote(String.valueOf(e)) + "}");
        }
    }

    /** org.json parses recursively: deep [[[[... would overflow the stack. Real requests nest 2-3 levels. */
    private static boolean tooDeep(byte[] b) {
        int depth = 0;
        boolean str = false, esc = false;
        for (byte c : b) {
            if (str) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') str = false;
            } else if (c == '"') str = true;
            else if (c == '[' || c == '{') { if (++depth > 32) return true; }
            else if (c == ']' || c == '}') depth--;
        }
        return false;
    }

    private void route(Socket s, String method, String path, JSONObject req) throws Exception {
        boolean get = method.equals("GET"), post = method.equals("POST");
        switch (path) {
            case "/apps":
                send(s, 200, PkgScan.report(ctx, verdicts).toString());
                return;
            case "/verdicts":
                if (get) {
                    send(s, 200, new JSONObject().put("pc", verdicts.pc()).put("you", verdicts.mine()).toString());
                } else if (post) {
                    JSONObject v = req.has("verdicts") ? req.getJSONObject("verdicts") : req;
                    int n = verdicts.putPc(v, req.optBoolean("replace", false));
                    send(s, 200, new JSONObject().put("ok", true).put("stored", n).toString());
                    events.onVerdicts(n);
                }
                return;
            case "/remote/start":
                if (!ScreenCapture.running()) {
                    ctx.startActivity(new Intent(ctx, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                send(s, 200, remoteStatus().toString());
                return;
            case "/remote/stop":
                ScreenCapture.stop();
                send(s, 200, remoteStatus().toString());
                return;
            case "/remote/status":
                send(s, 200, remoteStatus().toString());
                return;
            case "/screen.jpg": {
                byte[] jpg = ScreenCapture.jpeg();
                if (jpg == null) { send(s, 409, "{\"error\":\"screen capture is not running - POST /remote/start\"}"); return; }
                sendBytes(s, "image/jpeg", jpg);
                return;
            }
            case "/stream":
                stream(s);
                return;
            case "/input":
                send(s, 200, RemoteControl.input(ctx, req).toString());
                return;
            case "/net":
                if (post) Firewall.applyRemote(ctx, req);
                send(s, 200, Firewall.report(ctx).toString());
                return;
            case "/auto": { // {"action":"forcestop|disable","pkg":"..."} - waits for the result
                final String pkg = req.optString("pkg");
                final int action = "disable".equals(req.optString("action")) ? AutoClicker.DISABLE : AutoClicker.FORCE_STOP;
                if (!AutoClicker.available()) { send(s, 409, "{\"error\":\"turn on HU Launcher remote in Accessibility\"}"); return; }
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final String[] result = new String[2];
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        AutoClicker.run(ctx, pkg, action, (ok, msg) -> { result[0] = String.valueOf(ok); result[1] = msg; latch.countDown(); }));
                boolean finished = latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
                send(s, 200, new JSONObject().put("ok", finished && "true".equals(result[0]))
                        .put("message", finished ? result[1] : "timed out").toString());
                return;
            }
            case "/notify": // POST {"text":"...","secs":120}   silent shade notification; empty text removes it
                if (!post) { send(s, 405, "{\"error\":\"POST {\\\"text\\\":...}\"}"); return; }
                String text = req.optString("text").trim();
                if (text.length() > 200) text = text.substring(0, 200);
                send(s, 200, new JSONObject().put("ok", true)
                        .put("shown", Notice.show(ctx, text, req.optInt("secs", 120))).toString());
                return;
            case "/stats":
                send(s, 200, Monitor.report(ctx).toString());
                return;
            case "/spotify": // GET status   POST {"keep":true|false}
                if (post && req.has("keep")) {
                    final boolean keep = req.getBoolean("keep");
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> SpotifyKeeper.setEnabled(ctx, keep));
                    Thread.sleep(1500);
                }
                send(s, 200, new JSONObject().put("keep", SpotifyKeeper.enabled(ctx)).put("state", SpotifyKeeper.state())
                        .put("service", String.valueOf(SpotifyKeeper.mediaService(ctx))).toString());
                return;
            case "/keep": // GET list   POST {"add":[..],"remove":[..]}
                JSONObject refused = post ? Keeper.applyRemote(ctx, req) : new JSONObject();
                send(s, 200, Keeper.report(ctx).put("refused", refused).toString());
                return;
            case "/install": // GET status/version   POST {"bluetooth":true} (only names the file) | {"path":"/sdcard/x.apk"}
                if (post) {
                    if (req.optBoolean("bluetooth")) { // never installs by itself: the PC confirms the exact path
                        java.io.File b = Installer.newestBluetoothApk();
                        if (b == null) { send(s, 404, "{\"error\":\"no APK found in the Bluetooth folder\"}"); return; }
                        send(s, 200, new JSONObject().put("path", b.getPath()).put("file", b.getName())
                                .put("bytes", b.length()).put("modified", b.lastModified()).toString());
                        return;
                    }
                    java.io.File f = req.has("path") ? new java.io.File(req.getString("path")) : null;
                    if (f == null) { send(s, 400, "{\"error\":\"give a path\"}"); return; }
                    int id = Installer.installFile(ctx, f);
                    send(s, 200, new JSONObject().put("ok", true).put("session", id).put("file", f.getName())
                            .put("helper", AutoClicker.available()).toString());
                } else {
                    send(s, 200, Installer.status(ctx).toString());
                }
                return;
            default:
                send(s, 404, "{\"error\":\"not found\"}");
        }
    }

    private static JSONObject remoteStatus() throws Exception {
        return new JSONObject().put("capturing", ScreenCapture.running())
                .put("touch", RemoteControl.ready())
                .put("hint", RemoteControl.ready() ? "" : "Turn on \"HU Launcher remote & helper\" in Settings > Accessibility for touch control");
    }

    /** MJPEG: a new part only when the screen actually changed, at most ~8 fps. */
    private void stream(Socket s) throws IOException, InterruptedException {
        if (streams.incrementAndGet() > MAX_STREAMS) {
            streams.decrementAndGet();
            send(s, 503, "{\"error\":\"too many viewers\"}");
            return;
        }
        try { streamFrames(s); } finally { streams.decrementAndGet(); }
    }

    private void streamFrames(Socket s) throws IOException, InterruptedException {
        if (!ScreenCapture.running()) { send(s, 409, "{\"error\":\"screen capture is not running\"}"); return; }
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n"
                + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        long seen = -1;
        while (running && ScreenCapture.running()) {
            long v = ScreenCapture.awaitFrame(seen, 2000);
            if (v == seen) continue;
            seen = v;
            byte[] jpg = ScreenCapture.jpeg();
            if (jpg == null) continue;
            out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpg.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(jpg);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(120);
        }
    }

    private void sendPage(Socket s) throws IOException {
        try (InputStream in = ctx.getResources().openRawResource(R.raw.remote)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; ) b.write(buf, 0, r);
            sendBytes(s, "text/html; charset=utf-8", b.toByteArray());
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("line too long");
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void send(Socket s, int code, String json) throws IOException {
        sendRaw(s, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private void sendSystemApk(Socket s, String pkg) throws IOException {
        java.io.File f;
        try {
            if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) { send(s, 400, "{\"error\":\"bad package name\"}"); return; }
            android.content.pm.ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0x2000 /* MATCH_DISABLED_COMPONENTS|UNINSTALLED */);
            f = new java.io.File(ai.sourceDir).getCanonicalFile();
            boolean preinstalled = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            String p = f.getPath();
            if (!preinstalled || !(p.startsWith("/system/") || p.startsWith("/vendor/") || p.startsWith("/product/")
                    || p.startsWith("/odm/") || p.startsWith("/oem/")) || !p.endsWith(".apk")) {
                send(s, 403, "{\"error\":\"only preinstalled system apps\"}");
                return;
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            send(s, 404, "{\"error\":\"not installed\"}");
            return;
        }
        long len = f.length();
        if (len <= 0 || len > 200L * 1024 * 1024) { send(s, 413, "{\"error\":\"unreadable or too large\"}"); return; }
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: " + len
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
        }
        out.flush();
    }

    private static void sendBytes(Socket s, String type, byte[] body) throws IOException {
        sendRaw(s, 200, type, body);
    }

    private static void sendRaw(Socket s, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n"
                + "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' blob: data:; connect-src 'self'; frame-ancestors 'none'; form-action 'none'; base-uri 'none'\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** This unit's LAN IPv4 address (Wi-Fi first), or null. Skips the firewall's tun address. */
    static String address() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    if (ni.getName().startsWith("wlan")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    static JSONArray toArray(Iterable<String> it) {
        JSONArray a = new JSONArray();
        for (String s : it) a.put(s);
        return a;
    }
}
