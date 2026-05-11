/**
 * ============================================================
 *  Smart Car Parking System -- CLIENT SIDE
 *  File    : ParkingClient.java  v4.0
 *
 *  HOW TO RUN (Single PC simulation):
 *  1. javac ParkingServer.java && java ParkingServer  (Terminal 1)
 *  2. javac ParkingClient.java && java ParkingClient  (Terminal 2)
 *  3. Open tabs:
 *     http://localhost:8080/?client=CLIENT-1
 *     http://localhost:8080/?client=CLIENT-2  ... etc
 *
 *  NEW: /api/suggest  -> asks server for nearest slot (popup)
 *       /api/park     -> PARK|CLIENT (Dijkstra slot)
 *       /api/parkslot/N -> PARK|CLIENT|N (user chosen slot N)
 * ============================================================
 */

import java.net.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ParkingClient {

    // If server runs on same PC use 127.0.0.1
    // If server is on another laptop, use that laptop's Wi-Fi IP
    static final String SERVER_IP = "127.0.0.1";   // <-- CHANGE IF NEEDED

    static final int SERVER_PORT = 2100;
    static final int HTTP_PORT   = 8080;

    static DatagramSocket udpSocket;
    static InetAddress    serverAddr;

    static final Map<String, Integer> clientSlots   = new ConcurrentHashMap<>();
    static final Map<String, Boolean> clientWaiting = new ConcurrentHashMap<>();
    static final Map<String, Integer> clientWaitPos = new ConcurrentHashMap<>();

    static String ts() { return new SimpleDateFormat("HH:mm:ss").format(new Date()); }
    static void log(String m) { System.out.println("[" + ts() + "] " + m); }

    static synchronized String sendUDP(String message) throws Exception {
        byte[]         d   = message.getBytes();
        DatagramPacket out = new DatagramPacket(d, d.length, serverAddr, SERVER_PORT);
        udpSocket.send(out);
        log("UDP -> \"" + message + "\"");
        byte[]         buf = new byte[2048];
        DatagramPacket in  = new DatagramPacket(buf, buf.length);
        udpSocket.receive(in);
        String reply = new String(in.getData(), 0, in.getLength()).trim();
        log("UDP <- \"" + reply + "\"");
        return reply;
    }

    static void updateClientState(String clientId, String reply) {
        String[] p = reply.split("\\|");
        switch (p[0]) {
            case "PARKED": case "SLOT_TAKEN_PARKED":
                clientSlots.put(clientId, Integer.parseInt(p[1]));
                clientWaiting.put(clientId, false);
                clientWaitPos.put(clientId, -1);
                break;
            case "WAITING":
                clientSlots.put(clientId, -1);
                clientWaiting.put(clientId, true);
                clientWaitPos.put(clientId, Integer.parseInt(p[1]));
                break;
            case "FREED":
                clientSlots.put(clientId, -1);
                clientWaiting.put(clientId, false);
                clientWaitPos.put(clientId, -1);
                break;
            case "ALREADY_PARKED":
                if (p.length > 1) clientSlots.put(clientId, Integer.parseInt(p[1]));
                clientWaiting.put(clientId, false);
                break;
        }
    }

    static String extractClientId(String path) {
        String param = null;
        if (path.contains("client="))     param = path.substring(path.indexOf("client=") + 7);
        else if (path.contains("cid="))   param = path.substring(path.indexOf("cid=") + 4);
        if (param == null) return "CLIENT-1";
        int end = param.indexOf('&');
        return (end == -1 ? param : param.substring(0, end)).trim();
    }

    static String buildJson(String reply, String clientId) {
        int     mySlot  = clientSlots.getOrDefault(clientId, -1);
        boolean waiting = clientWaiting.getOrDefault(clientId, false);
        int     waitPos = clientWaitPos.getOrDefault(clientId, -1);
        return "{\"reply\":\""   + escapeJson(reply) + "\","
             + "\"clientId\":\"" + clientId           + "\","
             + "\"mySlot\":"    + mySlot              + ","
             + "\"waiting\":"   + waiting             + ","
             + "\"waitPos\":"   + waitPos             + "}";
    }

    static String escapeJson(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }

    static void startHttpServer() throws Exception {
        ServerSocket http = new ServerSocket(HTTP_PORT);
        log("HTTP ready -> open browser tabs with ?client=CLIENT-N");
        while (true) {
            Socket conn = http.accept();
            new Thread(() -> handleHttp(conn)).start();
        }
    }

    static void handleHttp(Socket conn) {
        try (
            BufferedReader in  = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            OutputStream   raw = conn.getOutputStream();
            PrintWriter    out = new PrintWriter(raw)
        ) {
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;
            String hdr;
            while ((hdr = in.readLine()) != null && !hdr.isEmpty()) {}

            String[] parts = requestLine.split(" ");
            String   path  = parts.length > 1 ? parts[1] : "/";
            log("HTTP " + parts[0] + " " + path);

            if (path.startsWith("/api/")) {
                String clientId = extractClientId(path);
                String udpMsg   = null;
                String body;

                if (path.startsWith("/api/suggest")) {
                    // Just ask server for suggestion, don't allocate yet
                    udpMsg = "SUGGEST|" + clientId;

                } else if (path.startsWith("/api/park")) {
                    // Regular park -- Dijkstra decides
                    udpMsg = "PARK|" + clientId;

                } else if (path.startsWith("/api/parkslot/")) {
                    // User chose a specific slot from the popup
                    String after = path.substring("/api/parkslot/".length());
                    String slot  = after.contains("?") ? after.substring(0, after.indexOf('?')) : after;
                    udpMsg = "PARK|" + clientId + "|" + slot.trim();

                } else if (path.startsWith("/api/exit/")) {
                    String after = path.substring("/api/exit/".length());
                    String slot  = after.contains("?") ? after.substring(0, after.indexOf('?')) : after;
                    udpMsg = "EXIT|" + clientId + "|" + slot.trim();

                } else if (path.startsWith("/api/status")) {
                    udpMsg = "STATUS";

                } else if (path.startsWith("/api/queue")) {
                    udpMsg = "QUEUE";

                } else if (path.startsWith("/api/clientinfo")) {
                    sendJson(out, raw, buildJson("OK", clientId));
                    return;
                }

                if (udpMsg != null) {
                    try {
                        String reply = sendUDP(udpMsg);
                        updateClientState(clientId, reply);
                        body = buildJson(reply, clientId);
                    } catch (Exception e) {
                        body = buildJson("ERROR|Cannot reach server.", clientId);
                        log("[X] UDP error: " + e.getMessage());
                    }
                } else {
                    body = buildJson("ERROR|Unknown endpoint", clientId);
                }
                sendJson(out, raw, body);
                return;
            }

            // Static files
            String filePath;
            if (path.equals("/") || path.startsWith("/?") || path.contains("index")) filePath = "index.html";
            else if (path.startsWith("/style.css"))  filePath = "style.css";
            else if (path.startsWith("/script.js"))  filePath = "script.js";
            else { out.print("HTTP/1.1 404 Not Found\r\n\r\nNot Found"); out.flush(); return; }

            File f = new File(filePath);
            if (!f.exists()) { out.print("HTTP/1.1 404 Not Found\r\n\r\nMissing: " + filePath); out.flush(); return; }

            String mime = filePath.endsWith(".css") ? "text/css"
                        : filePath.endsWith(".js")  ? "text/javascript" : "text/html";
            byte[] fb = new FileInputStream(f).readAllBytes();
            out.print("HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Length: "
                    + fb.length + "\r\nConnection: close\r\n\r\n");
            out.flush(); raw.write(fb); raw.flush();

        } catch (Exception ignored) {}
    }

    static void sendJson(PrintWriter out, OutputStream raw, String body) throws Exception {
        out.print("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Access-Control-Allow-Origin: *\r\nContent-Length: "
                + body.length() + "\r\nConnection: close\r\n\r\n" + body);
        out.flush();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("================================================");
        System.out.println("   Smart Car Parking Client v4.0");
        System.out.println("================================================");
        udpSocket = new DatagramSocket();
        udpSocket.setSoTimeout(3000);
        try { serverAddr = InetAddress.getByName(SERVER_IP); log("UDP -> " + SERVER_IP + ":" + SERVER_PORT); }
        catch (Exception e) { log("[X] Cannot resolve: " + SERVER_IP); }
        System.out.println("  Open tabs:");
        System.out.println("  http://localhost:8080/?client=CLIENT-1");
        System.out.println("  http://localhost:8080/?client=CLIENT-2");
        System.out.println("  ... up to CLIENT-20");
        System.out.println("------------------------------------------------");
        startHttpServer();
    }
}
