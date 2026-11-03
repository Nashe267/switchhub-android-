package za.co.switchgraphics.hub;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Listens for the "I'm here" broadcast every Switch Hub PC sends on the Wi-Fi (UDP 5758),
 * so the phone can find the PC without typing an address.
 */
class Discovery {
    static final int PORT = 5758;
    static final String TAG = "switchhub/1";

    final Context ctx;
    volatile boolean running;
    DatagramSocket sock;
    WifiManager.MulticastLock lock;
    final Map<String, JSONObject> found = new LinkedHashMap<>();

    Discovery(Context c) { ctx = c.getApplicationContext(); }

    void start() {
        running = true;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("switchhub-discovery");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) { }
        new Thread(this::loop, "discovery").start();
    }

    void stop() {
        running = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) { }
    }

    void loop() {
        try {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.setBroadcast(true);
            sock.setSoTimeout(1000);
            sock.bind(new InetSocketAddress(PORT));
            byte[] buf = new byte[2048];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException t) {
                    continue;
                }
                try {
                    JSONObject m = new JSONObject(new String(p.getData(), 0, p.getLength(), "UTF-8"));
                    if (!TAG.equals(m.optString("tag"))) continue;
                    String ip = p.getAddress().getHostAddress();
                    JSONObject pc = new JSONObject();
                    pc.put("id", m.optString("id"));
                    pc.put("name", m.optString("name", "Switch Hub PC"));
                    pc.put("ip", ip);
                    pc.put("url", "http://" + ip + ":" + m.optInt("port", 5757));
                    pc.put("seen", System.currentTimeMillis());
                    synchronized (found) { found.put(m.optString("id", ip), pc); }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        }
    }

    String foundJson() {
        JSONArray a = new JSONArray();
        long now = System.currentTimeMillis();
        synchronized (found) {
            for (JSONObject o : found.values()) if (now - o.optLong("seen") < 20000) a.put(o);
        }
        return a.toString();
    }
}
