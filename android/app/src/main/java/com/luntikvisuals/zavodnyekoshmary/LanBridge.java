package com.luntikvisuals.zavodnyekoshmary;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** window.WWLan: hosts the LAN relay inside the app and finds hosts on the same Wi-Fi via a UDP broadcast beacon. */
public final class LanBridge {
    static final int BEACON_PORT = 47392;
    private final Context ctx;
    private LanRelay relay;
    private volatile boolean beaconOn, discOn;
    private volatile String bName = "Хост", bState = "lobby";
    private volatile int bN = 1, bMax = 5;
    private DatagramSocket discSock;
    private WifiManager.MulticastLock mlock;
    private final Map<String, JSONObject> found = new ConcurrentHashMap<>();
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    // friends: presence beacons of other players and a small inbox for invites/answers sent by UDP unicast
    private final Map<String, JSONObject> pres = new ConcurrentHashMap<>();
    private final Map<String, Long> presSeen = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<JSONObject> box = new ConcurrentLinkedQueue<>();
    private volatile boolean presOn;
    private volatile String presJson = "{}";

    LanBridge(Context c) { ctx = c.getApplicationContext(); }

    @JavascriptInterface
    public synchronized String startHost(String name, int max) {
        JSONObject r = new JSONObject();
        try {
            stopHostInt();
            byte[] k = new byte[16];
            new SecureRandom().nextBytes(k);
            StringBuilder key = new StringBuilder();
            for (byte b : k) key.append(String.format("%02x", b & 0xff));
            LanRelay lr = new LanRelay(LanRelay.DEFAULT_PORT, key.toString());
            lr.start();
            relay = lr;
            bName = clip(name); bMax = Math.max(2, Math.min(12, max)); bN = 1; bState = "lobby";
            startBeacon();
            r.put("ok", true); r.put("port", lr.port()); r.put("key", key.toString()); r.put("ip", localIp());
        } catch (Exception e) {
            try { r.put("ok", false); r.put("err", "Порт занят или Wi-Fi недоступен"); } catch (Exception ignored) { }
        }
        return r.toString();
    }

    @JavascriptInterface
    public synchronized void stopHost() { stopHostInt(); }

    private void stopHostInt() {
        beaconOn = false;
        if (relay != null) { relay.stop(); relay = null; }
    }

    @JavascriptInterface
    public void updateBeacon(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("name")) bName = clip(o.optString("name"));
            bN = o.optInt("n", bN); bMax = o.optInt("max", bMax);
            String st = o.optString("state", bState);
            bState = st.length() > 12 ? st.substring(0, 12) : st;
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public String localIp() {
        String best = "";
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String n = ni.getName();
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) return a.getHostAddress();
                    if (best.isEmpty()) best = a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return best;
    }

    private void startBeacon() {
        beaconOn = true;
        Thread t = new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                while (beaconOn) {
                    JSONObject o = new JSONObject();
                    o.put("g", "WWLAN"); o.put("v", 1); o.put("name", bName); o.put("n", bN); o.put("max", bMax);
                    o.put("state", bState); o.put("port", relay != null ? relay.port() : LanRelay.DEFAULT_PORT);
                    byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
                    for (InetAddress dst : broadcastTargets()) {
                        try { s.send(new DatagramPacket(b, b.length, dst, BEACON_PORT)); } catch (Exception ignored) { }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) { }
        }, "lan-beacon");
        t.setDaemon(true);
        t.start();
    }

    private static List<InetAddress> broadcastTargets() {
        List<InetAddress> l = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) if (ia.getBroadcast() != null) l.add(ia.getBroadcast());
            }
            l.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) { }
        return l;
    }

    @JavascriptInterface
    public synchronized void startDiscovery() {
        if (discOn) return;
        discOn = true;
        found.clear(); seen.clear();
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { mlock = wm.createMulticastLock("ww-lan"); mlock.setReferenceCounted(false); mlock.acquire(); }
        } catch (Exception ignored) { }
        Thread t = new Thread(() -> {
            try {
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true); s.setBroadcast(true);
                s.bind(new InetSocketAddress(BEACON_PORT));
                s.setSoTimeout(1000);
                synchronized (LanBridge.this) { discSock = s; }
                byte[] buf = new byte[2048];
                String own = localIp();
                while (discOn) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        if (!"WWLAN".equals(o.optString("g"))) continue;
                        String ip = p.getAddress().getHostAddress();
                        String kind = o.optString("t", "");
                        if (kind.equals("p")) {
                            if (ip.equals(own)) continue;
                            String fid = o.optString("fid", "");
                            if (!fid.matches("[0-9a-f]{12}")) continue;
                            JSONObject e = new JSONObject();
                            e.put("fid", fid); e.put("nick", clip(o.optString("nick"))); e.put("ip", ip); e.put("hosting", o.optBoolean("hosting", false));
                            pres.put(fid, e); presSeen.put(fid, System.currentTimeMillis());
                            continue;
                        }
                        if (kind.equals("inv") || kind.equals("invr") || kind.equals("frq")) {
                            if (box.size() < 20) { o.put("ip", ip); box.add(o); }
                            continue;
                        }
                        if (relay != null && ip.equals(own)) continue;
                        JSONObject e = new JSONObject();
                        e.put("name", clip(o.optString("name", "Хост"))); e.put("ip", ip);
                        e.put("port", o.optInt("port", LanRelay.DEFAULT_PORT)); e.put("n", o.optInt("n", 1));
                        e.put("max", o.optInt("max", 5)); e.put("state", o.optString("state", "lobby"));
                        found.put(ip, e); seen.put(ip, System.currentTimeMillis());
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception ignored) { }
                }
                s.close();
            } catch (Exception ignored) { }
        }, "lan-discovery");
        t.setDaemon(true);
        t.start();
    }

    @JavascriptInterface
    public synchronized void stopDiscovery() {
        discOn = false;
        if (discSock != null) { discSock.close(); discSock = null; }
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Exception ignored) { }
        mlock = null;
    }

    @JavascriptInterface
    public String discovered() {
        JSONArray a = new JSONArray();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, JSONObject> e : found.entrySet()) {
            Long t = seen.get(e.getKey());
            if (t != null && now - t < 4000) a.put(e.getValue());
        }
        return a.toString();
    }

    @JavascriptInterface
    public void startPresence(String json) {
        presJson = json == null ? "{}" : json;
        if (presOn) return;
        presOn = true;
        Thread t = new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                while (presOn) {
                    try {
                        JSONObject in = new JSONObject(presJson), o = new JSONObject();
                        o.put("g", "WWLAN"); o.put("t", "p"); o.put("fid", in.optString("fid")); o.put("nick", clip(in.optString("nick")));
                        o.put("hosting", relay != null);
                        byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
                        for (InetAddress dst : broadcastTargets()) { try { s.send(new DatagramPacket(b, b.length, dst, BEACON_PORT)); } catch (Exception ignored) { } }
                    } catch (Exception ignored) { }
                    Thread.sleep(1500);
                }
            } catch (Exception ignored) { }
        }, "lan-presence");
        t.setDaemon(true);
        t.start();
    }

    @JavascriptInterface
    public void stopPresence() { presOn = false; }

    @JavascriptInterface
    public String presence() {
        JSONArray a = new JSONArray();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, JSONObject> e : pres.entrySet()) { Long t = presSeen.get(e.getKey()); if (t != null && now - t < 5000) a.put(e.getValue()); }
        return a.toString();
    }

    /** UDP unicast of a small JSON message (invite / answer) to a player on the same network. */
    @JavascriptInterface
    public boolean sendUdp(String ip, String json) {
        try {
            if (ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}") || json == null || json.length() > 1024) return false;
            JSONObject o = new JSONObject(json);
            o.put("g", "WWLAN");
            String t = o.optString("t");
            if (!(t.equals("inv") || t.equals("invr") || t.equals("frq"))) return false;
            final byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
            final InetAddress dst = InetAddress.getByName(ip);
            new Thread(() -> { try (DatagramSocket s = new DatagramSocket()) { s.send(new DatagramPacket(b, b.length, dst, BEACON_PORT)); } catch (Exception ignored) { } }, "lan-udp").start();
            return true;
        } catch (Exception e) { return false; }
    }

    @JavascriptInterface
    public String inbox() {
        JSONArray a = new JSONArray();
        JSONObject o;
        while ((o = box.poll()) != null) a.put(o);
        return a.toString();
    }

    public void shutdown() { presOn = false; stopDiscovery(); stopHost(); }

    private static String clip(String s) {
        if (s == null) return "Хост";
        s = s.replaceAll("[\\p{Cntrl}]", "").trim();
        return s.isEmpty() ? "Хост" : (s.length() > 20 ? s.substring(0, 20) : s);
    }
}
