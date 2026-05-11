/**
 * ============================================================
 *  Smart Car Parking System -- SERVER SIDE
 *  File    : ParkingServer.java
 *  Version : 4.0 -- 20 Slots, Pre-filled, Popup Suggestion
 *
 *  WHAT'S NEW:
 *  - 20 parking slots (was 5)
 *  - Slots 1-3 and 18-20 pre-filled at startup (simulation)
 *  - New SUGGEST command: runs Dijkstra, returns nearest free
 *    slot WITHOUT allocating -- used for the popup dialog
 *  - PARK|CLIENT|SLOT: allocate a specific slot (user choice)
 *  - PARK|CLIENT: allocate Dijkstra's recommended slot
 *
 *  PROTOCOL:
 *  SUGGEST|CLIENT-1         -> SUGGESTION|7|30  (nearest free slot + dist)
 *  PARK|CLIENT-1            -> PARKED|7|30      (allocate Dijkstra slot)
 *  PARK|CLIENT-1|12         -> PARKED|12|55     (allocate chosen slot)
 *  EXIT|CLIENT-1|7          -> FREED|7
 *  STATUS                   -> STATUS|0|1|0|... (20 values + OWNERS)
 *  QUEUE                    -> QUEUE|1:CLIENT-2|...
 * ============================================================
 */

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.text.SimpleDateFormat;

public class ParkingServer {

    // ---- Constants -----------------------------------------
    static final int SLOTS    = 20;  // total parking slots
    static final int V        = 21;  // graph nodes: 0=Entry, 1-20=Slots
    static final int MAX_WAIT = 20;

    // ---- Shared state --------------------------------------
    static int[]    slots     = new int[SLOTS];    // 0=free 1=occupied
    static String[] slotOwner = new String[SLOTS]; // who owns each slot
    static final Object LOCK  = new Object();

    // ---- Queues --------------------------------------------
    static final LinkedBlockingQueue<ParkRequest> parkQueue
            = new LinkedBlockingQueue<>();
    static final LinkedList<WaitEntry> waitList = new LinkedList<>();
    static final Map<String, Integer>  clientSlotMap = new ConcurrentHashMap<>();
    static final List<String>          arrivalLog
            = Collections.synchronizedList(new ArrayList<>());

    static DatagramSocket serverSocket;

    // ---- Graph: Entry(0) connected to all 20 slots ---------
    // Distances represent physical metres from entrance
    // Slots 1-5: nearest row, 6-10: second row, etc.
    static final int[][] graph = buildGraph();

    static int[][] buildGraph() {
        int[][] g = new int[V][V];
        // Distances from entry to each slot (in metres)
        int[] distances = {
             0,              // node 0 = entry (unused)
            10, 15, 20, 25, 30,   // slots 1-5   (nearest row)
            35, 40, 45, 50, 55,   // slots 6-10  (second row)
            60, 65, 70, 75, 80,   // slots 11-15 (third row)
            85, 90, 95,100,105    // slots 16-20 (farthest row)
        };
        for (int i = 1; i <= SLOTS; i++) {
            g[0][i] = distances[i];
            g[i][0] = distances[i];
        }
        return g;
    }

    // ---- Inner classes -------------------------------------
    static class ParkRequest {
        String clientId; InetAddress address; int port; long arrivalMs;
        boolean specific;  // true if client chose a specific slot
        int     chosenSlot; // only valid if specific=true
        ParkRequest(String id, InetAddress a, int p, boolean spec, int slot) {
            clientId = id; address = a; port = p;
            arrivalMs = System.currentTimeMillis();
            specific = spec; chosenSlot = slot;
        }
    }

    static class WaitEntry {
        String clientId; InetAddress address; int port; long waitSinceMs;
        WaitEntry(String id, InetAddress a, int p) {
            clientId = id; address = a; port = p;
            waitSinceMs = System.currentTimeMillis();
        }
    }

    // ---- Dijkstra ------------------------------------------
    static int minDistance(int[] dist, boolean[] visited) {
        int min = Integer.MAX_VALUE, idx = -1;
        for (int v = 0; v < V; v++) {
            if (!visited[v] && dist[v] <= min) { min = dist[v]; idx = v; }
        }
        return idx;
    }

    static int[] dijkstra(int src) {
        int[]     dist = new int[V];
        boolean[] spt  = new boolean[V];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[src] = 0;
        for (int c = 0; c < V - 1; c++) {
            int u = minDistance(dist, spt);
            if (u == -1) break;
            spt[u] = true;
            for (int v = 0; v < V; v++) {
                if (!spt[v] && graph[u][v] != 0
                        && dist[u] != Integer.MAX_VALUE
                        && dist[u] + graph[u][v] < dist[v]) {
                    dist[v] = dist[u] + graph[u][v];
                }
            }
        }
        return dist;
    }

    // Find nearest FREE slot using Dijkstra. Returns {slotNum, distance} or {-1,-1}
    static int[] findNearest() {
        int[] dist = dijkstra(0);
        int bestSlot = -1, bestDist = Integer.MAX_VALUE;
        for (int i = 1; i <= SLOTS; i++) {
            if (slots[i-1] == 0 && dist[i] < bestDist) {
                bestDist = dist[i];
                bestSlot = i;
            }
        }
        return new int[]{ bestSlot, bestSlot == -1 ? -1 : bestDist };
    }

    // Allocate a specific slot. Returns distance or -1 if not free.
    static int allocateSpecific(String clientId, int slotNum) {
        if (slotNum < 1 || slotNum > SLOTS) return -1;
        if (slots[slotNum-1] != 0) return -1;
        slots[slotNum-1]     = 1;
        slotOwner[slotNum-1] = clientId;
        clientSlotMap.put(clientId, slotNum);
        log("[ALLOC] " + clientId + " -> Slot " + slotNum
                + " (" + graph[0][slotNum] + "m). Slots: " + slotsStr());
        return graph[0][slotNum];
    }

    // ---- Utilities -----------------------------------------
    static String ts() { return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()); }
    static void log(String m) { System.out.println("[" + ts() + "] " + m); }

    static String slotsStr() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) sb.append(",");
            sb.append(slots[i]);
        }
        return sb.append("]").toString();
    }

    static void sendReply(String msg, InetAddress addr, int port) {
        try {
            byte[] d = msg.getBytes();
            serverSocket.send(new DatagramPacket(d, d.length, addr, port));
        } catch (Exception e) { log("[ERR] send: " + e.getMessage()); }
    }

    static void printPriorityLog() {
        System.out.println("  +-- Priority/Arrival Log ---------------------+");
        synchronized (arrivalLog) {
            for (int i = 0; i < arrivalLog.size(); i++)
                System.out.printf("  | %2d. %s%n", i+1, arrivalLog.get(i));
            if (arrivalLog.isEmpty()) System.out.println("  |  (empty)");
        }
        System.out.println("  +-- Wait List (" + waitList.size() + ") ----------------------+");
        if (waitList.isEmpty()) System.out.println("  |  (empty)");
        else {
            int pos = 1;
            for (WaitEntry w : waitList) {
                long sec = (System.currentTimeMillis() - w.waitSinceMs)/1000;
                System.out.printf("  | %2d. %s (waiting %ds)%n", pos++, w.clientId, sec);
            }
        }
        System.out.println("  +--------------------------------------------+");
    }

    // ---- SUGGEST handler -----------------------------------
    // Runs Dijkstra, returns nearest free slot WITHOUT allocating
    static void handleSuggest(String clientId, InetAddress addr, int port) {
        synchronized (LOCK) {
            int[] result = findNearest();
            if (result[0] == -1) {
                sendReply("FULL", addr, port);
            } else {
                // Also send top 3 nearest free slots for the popup menu
                int[]     dist    = dijkstra(0);
                List<int[]> opts  = new ArrayList<>();
                for (int i = 1; i <= SLOTS && opts.size() < 5; i++) {
                    if (slots[i-1] == 0) opts.add(new int[]{i, dist[i]});
                }
                // Sort by distance
                opts.sort((a, b) -> a[1] - b[1]);
                StringBuilder sb = new StringBuilder("SUGGESTION|" + result[0] + "|" + result[1]);
                // Append up to 5 alternatives
                for (int[] opt : opts) {
                    sb.append("|").append(opt[0]).append(":").append(opt[1]);
                }
                sendReply(sb.toString(), addr, port);
                log("[SUGGEST] " + clientId + " -> recommended Slot " + result[0]
                        + " (" + result[1] + "m)");
            }
        }
    }

    // ---- Park Queue Processor (FCFS) -----------------------
    static class ParkQueueProcessor implements Runnable {
        public void run() {
            log("[PROCESSOR] Park queue started (FCFS).");
            while (true) {
                try {
                    ParkRequest req = parkQueue.take();
                    synchronized (LOCK) {
                        long waited = System.currentTimeMillis() - req.arrivalMs;
                        log("[PROCESSOR] " + req.clientId + " (waited " + waited + "ms)");

                        if (clientSlotMap.containsKey(req.clientId)) {
                            int ex = clientSlotMap.get(req.clientId);
                            sendReply("ALREADY_PARKED|" + ex + "|" + req.clientId,
                                      req.address, req.port);
                            continue;
                        }

                        int slot, dist;
                        if (req.specific) {
                            // Client chose their own slot
                            dist = allocateSpecific(req.clientId, req.chosenSlot);
                            slot = dist >= 0 ? req.chosenSlot : -1;
                            if (dist < 0) {
                                // Chosen slot taken -- fall back to nearest
                                int[] nearest = findNearest();
                                slot = nearest[0];
                                if (slot != -1) {
                                    dist = allocateSpecific(req.clientId, slot);
                                    sendReply("SLOT_TAKEN_PARKED|" + slot + "|" + dist
                                              + "|" + req.clientId, req.address, req.port);
                                    updateLog(req.clientId, slot, dist, false);
                                    printPriorityLog();
                                    continue;
                                }
                            }
                        } else {
                            // Dijkstra recommended slot
                            int[] nearest = findNearest();
                            slot = nearest[0];
                            dist = slot != -1 ? allocateSpecific(req.clientId, slot) : -1;
                        }

                        if (slot != -1 && dist >= 0) {
                            sendReply("PARKED|" + slot + "|" + dist + "|" + req.clientId,
                                      req.address, req.port);
                            updateLog(req.clientId, slot, dist, false);
                        } else {
                            // Full -- add to wait list
                            if (waitList.size() >= MAX_WAIT) {
                                sendReply("FULL|NO_WAIT", req.address, req.port);
                            } else {
                                waitList.add(new WaitEntry(req.clientId, req.address, req.port));
                                int pos = waitList.size();
                                sendReply("WAITING|" + pos + "|" + req.clientId,
                                          req.address, req.port);
                                log("[WAIT] " + req.clientId + " queued at pos " + pos);
                                synchronized (arrivalLog) {
                                    arrivalLog.removeIf(e -> e.startsWith(req.clientId));
                                    arrivalLog.add(req.clientId + " [WAITING pos " + pos + "]");
                                }
                            }
                        }
                        printPriorityLog();
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    static void updateLog(String clientId, int slot, int dist, boolean afterWait) {
        synchronized (arrivalLog) {
            arrivalLog.removeIf(e -> e.startsWith(clientId));
            arrivalLog.add(clientId + " -> Slot " + slot + " (" + dist + "m)"
                    + (afterWait ? " [after wait]" : " [PARKED]"));
        }
    }

    // ---- EXIT handler --------------------------------------
    static String handleExit(String clientId, int slotNum) {
        synchronized (LOCK) {
            if (slotNum < 1 || slotNum > SLOTS) return "ERROR|Invalid slot (1-" + SLOTS + ")";
            if (slots[slotNum-1] == 0)           return "ERROR|Slot " + slotNum + " already free";

            slots[slotNum-1]     = 0;
            slotOwner[slotNum-1] = null;
            clientSlotMap.remove(clientId);
            log("[EXIT] " + clientId + " freed Slot " + slotNum);

            synchronized (arrivalLog) {
                arrivalLog.removeIf(e -> e.startsWith(clientId));
                arrivalLog.add(clientId + " [EXITED Slot " + slotNum + "]");
            }

            // Serve next waiting client
            if (!waitList.isEmpty()) {
                WaitEntry next  = waitList.removeFirst();
                int[]     near  = findNearest();
                int       ns    = near[0];
                if (ns != -1) {
                    int nd    = allocateSpecific(next.clientId, ns);
                    long wait = (System.currentTimeMillis() - next.waitSinceMs)/1000;
                    sendReply("PARKED|" + ns + "|" + nd + "|" + next.clientId,
                              next.address, next.port);
                    log("[AUTO] " + next.clientId + " auto-allocated Slot "
                            + ns + " after " + wait + "s");
                    updateLog(next.clientId, ns, nd, true);
                    int pos = 1;
                    for (WaitEntry w : waitList) {
                        sendReply("QUEUE_UPDATE|" + pos++ + "|" + w.clientId,
                                  w.address, w.port);
                    }
                }
            }
            printPriorityLog();
            return "FREED|" + slotNum + "|" + clientId;
        }
    }

    // ---- Dispatcher (called per packet in new thread) ------
    static void dispatch(String msg, InetAddress addr, int port) {
        log("IN [" + addr.getHostAddress() + ":" + port + "] \"" + msg + "\"");
        String[] p   = msg.split("\\|");
        String   cmd = p[0].toUpperCase().trim();

        switch (cmd) {

            case "SUGGEST": {
                String cid = p.length > 1 ? p[1].trim() : "UNKNOWN";
                handleSuggest(cid, addr, port);
                break;
            }

            case "PARK": {
                if (p.length < 2) { sendReply("ERROR|Format: PARK|CLIENT-ID", addr, port); return; }
                String cid      = p[1].trim();
                boolean specific = p.length >= 3;
                int     chosen   = 0;
                if (specific) {
                    try { chosen = Integer.parseInt(p[2].trim()); }
                    catch (NumberFormatException e) { specific = false; }
                }

                synchronized (arrivalLog) {
                    boolean exists = arrivalLog.stream()
                            .anyMatch(e -> e.startsWith(cid) && !e.contains("EXITED"));
                    if (!exists) arrivalLog.add(cid + " [arrived " + ts() + "]");
                }

                try { parkQueue.put(new ParkRequest(cid, addr, port, specific, chosen)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                break;
            }

            case "EXIT": {
                if (p.length < 3) { sendReply("ERROR|Format: EXIT|CLIENT-ID|SLOT", addr, port); return; }
                String cid = p[1].trim();
                try {
                    int s = Integer.parseInt(p[2].trim());
                    sendReply(handleExit(cid, s), addr, port);
                } catch (NumberFormatException e) {
                    sendReply("ERROR|Invalid slot number", addr, port);
                }
                break;
            }

            case "STATUS": {
                StringBuilder sb = new StringBuilder("STATUS");
                for (int i = 0; i < SLOTS; i++) sb.append("|").append(slots[i]);
                sb.append("|OWNERS");
                for (int i = 0; i < SLOTS; i++)
                    sb.append("|").append(slotOwner[i] == null ? "FREE" : slotOwner[i]);
                sendReply(sb.toString(), addr, port);
                break;
            }

            case "QUEUE": {
                synchronized (LOCK) {
                    StringBuilder sb = new StringBuilder("QUEUE");
                    if (waitList.isEmpty()) {
                        sb.append("|EMPTY");
                    } else {
                        int pos = 1;
                        for (WaitEntry w : waitList) {
                            long sec = (System.currentTimeMillis() - w.waitSinceMs)/1000;
                            sb.append("|").append(pos++).append(":")
                              .append(w.clientId).append("(").append(sec).append("s)");
                        }
                    }
                    sendReply(sb.toString(), addr, port);
                }
                break;
            }

            default:
                sendReply("ERROR|Unknown command", addr, port);
        }
    }

    // ---- UDP Receiver --------------------------------------
    static class UDPReceiver implements Runnable {
        public void run() {
            byte[] buf = new byte[2048];
            log("[UDP] Receiver on port 2100.");
            while (true) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    serverSocket.receive(pkt);
                    final String      m = new String(pkt.getData(), 0, pkt.getLength()).trim();
                    final InetAddress a = pkt.getAddress();
                    final int         p = pkt.getPort();
                    new Thread(() -> dispatch(m, a, p)).start();
                } catch (Exception e) { log("[ERR] receive: " + e.getMessage()); }
            }
        }
    }

    // ---- Main ----------------------------------------------
    public static void main(String[] args) throws Exception {

        // Init all slots free
        Arrays.fill(slots, 0);
        Arrays.fill(slotOwner, null);

        // Pre-fill slots 1-3 (simulation: already occupied)
        String[] preFillNames = {"SIM-A","SIM-B","SIM-C"};
        for (int i = 0; i < 3; i++) {
            slots[i]     = 1;
            slotOwner[i] = preFillNames[i];
            clientSlotMap.put(preFillNames[i], i+1);
        }
        // Pre-fill slots 18-20 (simulation: already occupied)
        String[] preBack = {"SIM-X","SIM-Y","SIM-Z"};
        for (int i = 0; i < 3; i++) {
            slots[17+i]     = 1;
            slotOwner[17+i] = preBack[i];
            clientSlotMap.put(preBack[i], 18+i);
        }

        serverSocket = new DatagramSocket(2100);

        System.out.println("+==============================================+");
        System.out.println("|  Smart Car Parking Server v4.0               |");
        System.out.println("|  20 Slots  |  UDP 2100  |  20 Clients        |");
        System.out.println("+==============================================+");
        log("Pre-filled: Slots 1-3 (SIM-A/B/C) and Slots 18-20 (SIM-X/Y/Z)");
        log("Free slots: 4-17 (14 slots available)");
        System.out.println("------------------------------------------------");

        Thread t1 = new Thread(new ParkQueueProcessor(), "ParkQueue");
        Thread t2 = new Thread(new UDPReceiver(), "UDPReceiver");
        t1.setDaemon(true); t2.setDaemon(true);
        t1.start(); t2.start();

        log("Ready. Waiting for clients...");
        Thread.currentThread().join();
    }
}
