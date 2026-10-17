package com.luntikvisuals.zavodnyekoshmary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local-network relay for «Windup Wonderland» multiplayer. Plain Java (no Android classes), so it also runs on a desktop
 * JVM for tests. A minimal RFC 6455 WebSocket server:
 *   /host?key=K  — exactly one connection, only from loopback and only with the one-time key: the host's own WebView.
 *   /peer        — players on the same Wi-Fi.
 * Peer text messages are forwarded to the host as {"k":"pm","d":&lt;msg&gt;,"id":N}; the host sends "@N|json" (one peer)
 * or "@*|json" (all peers). Joins/leaves are reported as {"k":"pj","id":N} / {"k":"pl","id":N}.
 * No game logic and no data storage here; the host's simulation is authoritative.
 */
public final class LanRelay {
    public static final int DEFAULT_PORT = 47391;
    static final int MAX_PEERS = 12, MAX_PEER_MSG = 64 * 1024, MAX_HOST_MSG = 2 * 1024 * 1024;
    static final double PEER_RATE = 60, PEER_BURST = 120;

    private final String key;
    private final int port;
    private ServerSocket server;
    private volatile boolean running;
    private volatile Conn host;
    private final Map<Integer, Conn> peers = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public LanRelay(int port, String key) { this.port = port; this.key = key; }

    public int port() { return server != null ? server.getLocalPort() : port; }
    public boolean isRunning() { return running; }
    public int peerCount() { return peers.size(); }

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port), 16);
        server = s;
        running = true;
        Thread t = new Thread(this::acceptLoop, "lan-relay-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        Conn h = host; host = null;
        if (h != null) h.close(1001, "stop");
        for (Conn c : peers.values()) c.close(4001, "host_left");
        peers.clear();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket sock = server.accept();
                sock.setTcpNoDelay(true);
                sock.setSoTimeout(0);
                Thread t = new Thread(() -> handle(sock), "lan-relay-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    // ---------------------------------------------------------------- connection handling
    private void handle(Socket sock) {
        Conn c = null;
        try {
            sock.setSoTimeout(8000);
            InputStream in = sock.getInputStream();
            String req = readHeaders(in);
            if (req == null) { sock.close(); return; }
            String[] lines = req.split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2 || !"GET".equals(first[0])) { httpError(sock, 400); return; }
            String path = first[1];
            String wsKey = null, upgrade = null;
            for (int i = 1; i < lines.length; i++) {
                int p = lines[i].indexOf(':');
                if (p <= 0) continue;
                String n = lines[i].substring(0, p).trim().toLowerCase(), v = lines[i].substring(p + 1).trim();
                if (n.equals("sec-websocket-key")) wsKey = v;
                else if (n.equals("upgrade")) upgrade = v.toLowerCase();
            }
            if (path.equals("/health")) { httpText(sock, "ok"); return; }
            if (wsKey == null || upgrade == null || !upgrade.contains("websocket")) { httpError(sock, 400); return; }
            boolean wantHost = path.startsWith("/host");
            if (wantHost) {
                String k = query(path, "key");
                InetAddress a = sock.getInetAddress();
                if (k == null || !constEq(k, key) || a == null || !a.isLoopbackAddress() || host != null) { httpError(sock, 403); return; }
            } else if (!path.startsWith("/peer")) { httpError(sock, 404); return; }
            else if (host == null || peers.size() >= MAX_PEERS) { httpError(sock, 503); return; }

            String accept = b64(MessageDigest.getInstance("SHA-1")
                    .digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
            OutputStream out = sock.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                    + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            sock.setSoTimeout(0);
            c = new Conn(sock, in, out, wantHost ? 0 : nextId.getAndIncrement(), wantHost);
            if (wantHost) host = c;
            else {
                peers.put(c.id, c);
                Conn h = host;
                if (h != null) h.sendText("{\"k\":\"pj\",\"id\":" + c.id + "}");
            }
            readLoop(c);
        } catch (Exception e) {
            // connection ends
        } finally {
            if (c != null) onClosed(c);
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    private void onClosed(Conn c) {
        if (c.isHost) {
            if (host == c) host = null;
            for (Conn p : peers.values()) p.close(4001, "host_left");
            peers.clear();
        } else if (peers.remove(c.id) != null) {
            Conn h = host;
            if (h != null) h.sendText("{\"k\":\"pl\",\"id\":" + c.id + "}");
        }
    }

    private void readLoop(Conn c) throws IOException {
        ByteArrayOutputStream frag = null;
        int fragOp = 0;
        int limit = c.isHost ? MAX_HOST_MSG : MAX_PEER_MSG;
        while (running && !c.closed) {
            int b0 = c.in.read(), b1 = c.in.read();
            if (b0 < 0 || b1 < 0) return;
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) len = ((long) readN(c.in, 1) << 8) | readN(c.in, 1);
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | readN(c.in, 1); }
            if (!masked) { c.close(1002, "mask"); return; }
            if (len < 0 || len > limit) { c.close(1009, "too big"); return; }
            byte[] mask = readBytes(c.in, 4);
            byte[] data = readBytes(c.in, (int) len);
            for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
            if (op == 8) { c.close(1000, ""); return; }
            if (op == 9) { c.sendFrame(10, data); continue; }
            if (op == 10) continue;
            if (op == 0) {
                if (frag == null) { c.close(1002, "cont"); return; }
                frag.write(data);
                if (frag.size() > limit) { c.close(1009, "too big"); return; }
                if (!fin) continue;
                data = frag.toByteArray(); op = fragOp; frag = null;
            } else if (!fin) {
                frag = new ByteArrayOutputStream(); frag.write(data); fragOp = op; continue;
            }
            if (op != 1) continue; // binary is not used
            onText(c, new String(data, StandardCharsets.UTF_8));
        }
    }

    private void onText(Conn c, String s) {
        if (c.isHost) {
            if (s.length() < 3 || s.charAt(0) != '@') return;
            int bar = s.indexOf('|');
            if (bar < 2) return;
            String to = s.substring(1, bar), msg = s.substring(bar + 1);
            if (to.equals("*")) { for (Conn p : peers.values()) p.sendText(msg); return; }
            if (to.startsWith("x")) { // "@x5|" closes peer 5
                try { Conn p = peers.get(Integer.parseInt(to.substring(1))); if (p != null) p.close(4002, "kick"); } catch (NumberFormatException ignored) { }
                return;
            }
            try { Conn p = peers.get(Integer.parseInt(to)); if (p != null) p.sendText(msg); } catch (NumberFormatException ignored) { }
            return;
        }
        if (!c.allow()) return;
        if (!isJsonObject(s)) return;
        Conn h = host;
        if (h != null) h.sendText("{\"k\":\"pm\",\"d\":" + s + ",\"id\":" + c.id + "}");
    }

    /** One JSON object and nothing after it (brace/bracket balance outside strings), so a peer can't forge relay fields. */
    static boolean isJsonObject(String s) {
        int n = s.length();
        if (n < 2 || s.charAt(0) != '{' || s.charAt(n - 1) != '}') return false;
        int depth = 0; boolean str = false, esc = false;
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (str) { if (esc) esc = false; else if (ch == '\\') esc = true; else if (ch == '"') str = false; else if (ch < 0x20) return false; continue; }
            if (ch == '"') str = true;
            else if (ch == '{' || ch == '[') depth++;
            else if (ch == '}' || ch == ']') { depth--; if (depth < 0) return false; if (depth == 0 && i != n - 1) return false; }
        }
        return depth == 0 && !str;
    }

    // ---------------------------------------------------------------- helpers
    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < 8192) {
            int x = in.read();
            if (x < 0) return null;
            b.write(x);
            if ((state == 0 || state == 2) && x == '\r') state++;
            else if ((state == 1 || state == 3) && x == '\n') { state++; if (state == 4) return b.toString("ISO-8859-1"); }
            else state = 0;
        }
        return null;
    }
    private static int readN(InputStream in, int n) throws IOException { int x = in.read(); if (x < 0) throw new IOException("eof"); return x; }
    private static byte[] readBytes(InputStream in, int n) throws IOException {
        byte[] b = new byte[n]; int o = 0;
        while (o < n) { int r = in.read(b, o, n - o); if (r < 0) throw new IOException("eof"); o += r; }
        return b;
    }
    private static String query(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        for (String kv : path.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) {
                try { return java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8"); } catch (Exception ex) { return null; }
            }
        }
        return null;
    }
    private static String b64(byte[] d) {
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b = (d[i] & 0xff) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xff : 0);
            sb.append(A.charAt(b >> 18 & 63)).append(A.charAt(b >> 12 & 63));
            sb.append(i + 1 < d.length ? A.charAt(b >> 6 & 63) : '=').append(i + 2 < d.length ? A.charAt(b & 63) : '=');
        }
        return sb.toString();
    }
    private static boolean constEq(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(x, y);
    }
    private static void httpError(Socket s, int code) {
        try { s.getOutputStream().write(("HTTP/1.1 " + code + " Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)); s.close(); } catch (IOException ignored) { }
    }
    private static void httpText(Socket s, String t) {
        try { byte[] b = t.getBytes(StandardCharsets.UTF_8); s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)); s.getOutputStream().write(b); s.close(); } catch (IOException ignored) { }
    }

    static final class Conn {
        final Socket sock; final InputStream in; final OutputStream out; final int id; final boolean isHost;
        volatile boolean closed;
        double tokens = PEER_BURST; long last = System.nanoTime();
        Conn(Socket s, InputStream i, OutputStream o, int id, boolean host) { sock = s; in = i; out = o; this.id = id; isHost = host; }
        boolean allow() {
            long now = System.nanoTime();
            tokens = Math.min(PEER_BURST, tokens + (now - last) / 1e9 * PEER_RATE); last = now;
            if (tokens < 1) return false;
            tokens -= 1; return true;
        }
        void sendText(String s) { sendFrame(1, s.getBytes(StandardCharsets.UTF_8)); }
        synchronized void sendFrame(int op, byte[] data) {
            if (closed) return;
            try {
                int n = data.length;
                byte[] h;
                if (n < 126) h = new byte[]{(byte) (0x80 | op), (byte) n};
                else if (n < 65536) h = new byte[]{(byte) (0x80 | op), 126, (byte) (n >> 8), (byte) n};
                else { h = new byte[10]; h[0] = (byte) (0x80 | op); h[1] = 127; for (int i = 0; i < 8; i++) h[9 - i] = (byte) ((long) n >> (8 * i)); }
                out.write(h); out.write(data); out.flush();
            } catch (IOException e) { closed = true; try { sock.close(); } catch (IOException ignored) { } }
        }
        void close(int code, String reason) {
            if (closed) return;
            byte[] r = reason.getBytes(StandardCharsets.UTF_8);
            byte[] p = new byte[2 + r.length]; p[0] = (byte) (code >> 8); p[1] = (byte) code; System.arraycopy(r, 0, p, 2, r.length);
            sendFrame(8, p);
            closed = true;
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    /** Desktop test entry point: java LanRelay [port] [key] */
    public static void main(String[] a) throws Exception {
        int port = a.length > 0 ? Integer.parseInt(a[0]) : DEFAULT_PORT;
        String key = a.length > 1 ? a[1] : "testkey";
        LanRelay r = new LanRelay(port, key);
        r.start();
        System.out.println("LanRelay listening on " + r.port());
        while (r.isRunning()) Thread.sleep(1000);
    }
}
