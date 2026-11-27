package com.hu.launcher;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Userspace relay for the apps the firewall lets through.
 *
 * The VPN tunnel carries only the allowed apps (Firewall uses addAllowedApplication). Blocked
 * apps have no route at all, so with "Block connections without VPN" on they fail closed. The
 * allowed apps' packets come here: this class opens the real connections (each socket is
 * protect()ed so it leaves on the real network, not back into the tunnel) and answers the
 * apps with hand-built IPv4 packets.
 *
 * Scope: IPv4 TCP and UDP. ICMP, IPv6 and IP fragments are dropped (apps fall back to IPv4, and
 * the large MTU makes fragments rare). TCP is a small subset: handshake, in-order data both ways,
 * flow control in both directions, duplicate-ACK and timeout retransmits, FIN and RST.
 *
 * Limits: the number of flows and threads is capped, so one app can't take the process down.
 * Idle, every thread sleeps until there is something to do.
 */
final class Relay {
    /** Tunnel packet size. Large packets mean far less work per byte; the tunnel is virtual. */
    static final int MTU = 16000;
    private static final int LOCAL = ip(10, 88, 0, 1);      // the tunnel address apps talk from
    private static final int WINDOW = 65535;                // most bytes we keep in flight to the app
    private static final int UP_BUFFER = 128 * 1024;        // app data waiting for the server, per flow
    private static final int MAX_TCP = 256, MAX_UDP = 128;  // open flows
    private static final int STACK = 128 * 1024;            // relay threads need very little
    private static final int FIN = 0x01, SYN = 0x02, RST = 0x04, PSH = 0x08, ACK = 0x10;
    private static final long TCP_IDLE_MS = 2 * 60 * 60 * 1000L;   // push connections stay quiet for long
    private static final long TCP_EVICT_MS = 60 * 1000L;    // when full, only a flow this quiet makes room
    private static final long UDP_IDLE_MS = 60 * 1000L;
    private static final long DNS_IDLE_MS = 10 * 1000L;
    private static final long RTO_MS = 1000L;
    private static final int RTO_TRIES = 8;
    private static final AtomicInteger IP_ID = new AtomicInteger(1);
    private static final Random RND = new Random();
    private static final byte[] EOF = new byte[0];

    private final VpnService svc;
    private final FileInputStream in;
    private final FileOutputStream out;
    private final Runnable onDeath;
    private final Map<String, Tcp> tcps = new ConcurrentHashMap<>();
    private final Map<String, Udp> udps = new ConcurrentHashMap<>();
    private final AtomicInteger tcpThreads = new AtomicInteger(), udpThreads = new AtomicInteger();
    private volatile Thread sweeper;
    private volatile boolean longSleep;
    private volatile boolean stopped;

    /** onDeath runs if the relay stops by itself (not through stop()). */
    Relay(VpnService svc, ParcelFileDescriptor pfd, Runnable onDeath) {
        this.svc = svc;
        this.in = new FileInputStream(pfd.getFileDescriptor());
        this.out = new FileOutputStream(pfd.getFileDescriptor());
        this.onDeath = onDeath;
    }

    void start() {
        sweeper = new Thread(this::sweep, "fw-sweep");
        sweeper.setDaemon(true);
        sweeper.start();
        Thread r = new Thread(this::loop, "fw-relay");
        r.setDaemon(true);
        r.start();
    }

    /** Closes every relayed connection. The tunnel itself is closed by the caller. */
    void stop() {
        stopped = true;
        wake();
        for (Tcp c : tcps.values()) c.close();
        for (Udp u : udps.values()) u.sock.close();
    }

    private static boolean spawn(Runnable r, String name, AtomicInteger count) {
        count.incrementAndGet();
        try {
            Thread t = new Thread(null, () -> {
                try { r.run(); } finally { count.decrementAndGet(); }
            }, name, STACK);
            t.setDaemon(true);
            t.start();
            return true;
        } catch (OutOfMemoryError e) {   // no more threads: refuse this flow, keep the launcher alive
            count.decrementAndGet();
            return false;
        }
    }

    // ---- reading the tunnel --------------------------------------------------------------------

    private void loop() {
        byte[] buf = new byte[65535];
        try {
            int n;
            while (!stopped && (n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                try {
                    handle(buf, n);
                } catch (RuntimeException | OutOfMemoryError e) {
                    dropped();
                }
            }
        } catch (IOException ignored) {
            // tunnel closed
        } finally {
            boolean unexpected = !stopped;
            stop();
            if (unexpected) onDeath.run();
        }
    }

    private void handle(byte[] p, int n) {
        if (n < 20 || ((p[0] >> 4) & 0xf) != 4) { dropped(); return; }
        int ihl = (p[0] & 0xf) * 4;
        int total = u16(p, 2);
        if (ihl < 20 || total < ihl || total > n) { dropped(); return; }
        if ((u16(p, 6) & 0x3fff) != 0 || i32(p, 12) != LOCAL) { dropped(); return; }  // fragment or odd source
        int proto = p[9] & 0xff;
        int dst = i32(p, 16);
        if (proto == 6) tcpPacket(p, ihl, total, dst);
        else if (proto == 17) udpPacket(p, ihl, total, dst);
        else dropped();   // ICMP and the rest
    }

    private static void dropped() { Firewall.blockedPackets.incrementAndGet(); }

    // ---- timers ----------------------------------------------------------------------------------

    /** Retransmits and idle clean-up. Sleeps for as long as nothing can need it. */
    private void sweep() {
        while (!stopped) {
            longSleep = true;   // set before looking, so data sent meanwhile wakes us
            long now = System.currentTimeMillis();
            boolean busy = false;
            for (Tcp c : tcps.values()) busy |= c.tick(now);
            for (Udp u : udps.values()) if (now - u.last > u.idle) drop(u);
            if (busy) longSleep = false;
            if (stopped) return;
            if (busy) LockSupport.parkNanos(500 * 1000000L);
            else if (!udps.isEmpty()) LockSupport.parkNanos(5000 * 1000000L);
            else if (!tcps.isEmpty()) LockSupport.parkNanos(60000 * 1000000L);
            else LockSupport.park();
        }
    }

    private void wake() { LockSupport.unpark(sweeper); }

    // ---- UDP ------------------------------------------------------------------------------------

    private static final class Udp {
        final String key;
        final DatagramSocket sock;
        final int remote, rport, aport;
        final long idle;
        volatile long last = System.currentTimeMillis();

        Udp(String key, DatagramSocket sock, int remote, int rport, int aport) {
            this.key = key; this.sock = sock; this.remote = remote; this.rport = rport; this.aport = aport;
            this.idle = rport == 53 ? DNS_IDLE_MS : UDP_IDLE_MS;
        }
    }

    private void drop(Udp u) {
        udps.remove(u.key, u);
        u.sock.close();
    }

    private void udpPacket(byte[] p, int off, int total, int dst) {
        if (total - off < 8) { dropped(); return; }
        int sport = u16(p, off), dport = u16(p, off + 2);
        int len = u16(p, off + 4) - 8;
        if (len < 0 || off + 8 + len > total) { dropped(); return; }
        String key = "u" + sport + ":" + dst + ":" + dport;
        Udp u = udps.get(key);
        if (u == null) {
            if (udps.size() >= MAX_UDP) {   // full: the quietest flow makes room
                Udp oldest = null;
                for (Udp x : udps.values()) if (oldest == null || x.last < oldest.last) oldest = x;
                if (oldest != null) drop(oldest);
            }
            if (udpThreads.get() >= 2 * MAX_UDP) { dropped(); return; }
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                if (!svc.protect(s)) { s.close(); dropped(); return; }
                s.connect(new InetSocketAddress(addr(dst), dport));
            } catch (IOException | RuntimeException e) {
                if (s != null) s.close();
                dropped();
                return;
            }
            u = new Udp(key, s, dst, dport, sport);
            udps.put(key, u);
            final Udp created = u;
            if (!spawn(() -> udpPump(created), "fw-udp", udpThreads)) { drop(u); dropped(); return; }
            if (stopped) { drop(u); return; }
            wake();
        }
        u.last = System.currentTimeMillis();
        try {
            u.sock.send(new DatagramPacket(p, off + 8, len));
        } catch (IOException | RuntimeException e) {
            drop(u);
        }
    }

    /** Carries replies from the internet back to the app for one UDP flow. */
    private void udpPump(Udp u) {
        try {
            byte[] buf = new byte[MTU - 27];   // one spare byte shows a reply too big for the tunnel
            while (!stopped && !u.sock.isClosed()) {
                DatagramPacket d = new DatagramPacket(buf, buf.length);
                u.sock.receive(d);
                u.last = System.currentTimeMillis();
                if (d.getLength() > MTU - 28) dropped();
                else emitUdp(u.remote, u.rport, u.aport, buf, d.getLength());
            }
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            // idle timeout or closed
        } finally {
            drop(u);
        }
    }

    private void emitUdp(int src, int sport, int dport, byte[] data, int len) {
        int tot = 28 + len;
        byte[] pk = new byte[tot];
        ipHeader(pk, 17, src, LOCAL, tot);
        put16(pk, 20, sport);
        put16(pk, 22, dport);
        put16(pk, 24, 8 + len);
        put16(pk, 26, 0);
        System.arraycopy(data, 0, pk, 28, len);
        int c = transportSum(pk, 20, 8 + len, 17, src, LOCAL);
        put16(pk, 26, c == 0 ? 0xffff : c);
        emit(pk);
    }

    // ---- TCP ------------------------------------------------------------------------------------

    /** One segment we sent to the app and are waiting to see acknowledged. */
    private static final class Seg {
        final int seq, flags, len;
        final byte[] data;

        Seg(int seq, int flags, byte[] data) {
            this.seq = seq; this.flags = flags; this.data = data; this.len = data == null ? 0 : data.length;
        }

        int end() { return seq + len + ((flags & FIN) != 0 ? 1 : 0); }
    }

    private final class Tcp {
        final String key;
        final int rip, rport, aport;
        final int synSeq;     // the app's initial sequence number
        final int mss;        // largest payload the app accepts per packet
        final int isn = RND.nextInt();
        final ArrayDeque<Seg> unacked = new ArrayDeque<>();
        final LinkedBlockingQueue<byte[]> toRemote = new LinkedBlockingQueue<>();
        Socket sock;
        volatile boolean closed;
        boolean connected, appFin, finSent, eofWritten, writing;
        int appNext;      // next sequence number we expect from the app
        int ourSeq;       // next sequence number we send to the app
        int ackedSeq;     // highest of our bytes the app has acknowledged
        int peerWindow;   // how many unacknowledged bytes the app has room for
        int queued;       // bytes from the app not yet written to the server
        int advertised = WINDOW;
        int dupAcks, rtoTries;
        long lastSend = System.currentTimeMillis();
        volatile long last = lastSend;

        Tcp(String key, int rip, int rport, int aport, int synSeq, int mss, int window) {
            this.key = key; this.rip = rip; this.rport = rport; this.aport = aport;
            this.synSeq = synSeq; this.mss = mss; this.peerWindow = window;
            this.appNext = synSeq + 1;
        }

        /** Called on the relay thread for every packet the app sends on this flow. */
        synchronized void onPacket(byte[] p, int poff, int dl, int seq, int ack, int flags, int window) {
            if ((flags & RST) != 0) { close(); return; }
            if (!connected) return;   // still connecting: the app will retransmit
            last = System.currentTimeMillis();

            if ((flags & ACK) != 0) {
                int adv = ack - ackedSeq;
                if (adv >= 0 && adv <= ourSeq - ackedSeq) {
                    int before = peerWindow;
                    peerWindow = window;
                    if (window == 0) rtoTries = 0;   // the app is alive, its buffer is just full
                    if (adv > 0) {
                        ackedSeq = ack;
                        dupAcks = 0;
                        rtoTries = 0;
                        while (!unacked.isEmpty() && unacked.peekFirst().end() - ack <= 0) unacked.pollFirst();
                    } else if (before == 0 && window > 0) {
                        if (!unacked.isEmpty()) transmit(unacked.peekFirst());   // room again
                    } else if (dl == 0 && window == before && window > 0 && (flags & (SYN | FIN)) == 0
                            && !unacked.isEmpty() && ++dupAcks == 3) {
                        transmit(unacked.peekFirst());   // fast retransmit
                    }
                    if (adv > 0 || window != before) notifyAll();
                }
            }

            if (dl > 0) {
                if (seq == appNext && !appFin && queued + dl <= UP_BUFFER) {
                    toRemote.add(Arrays.copyOfRange(p, poff, poff + dl));
                    queued += dl;
                    appNext += dl;
                    startWriter();
                }
                ackApp();   // also answers duplicates and out-of-order data with what we have
            } else if ((flags & (SYN | FIN)) == 0 && seq == appNext - 1) {
                ackApp();   // keep-alive or window probe
            }

            if ((flags & FIN) != 0) {
                if (!appFin && seq + dl == appNext) {
                    toRemote.add(EOF);
                    appFin = true;
                    appNext += 1;
                    startWriter();
                    ackApp();
                } else if (appFin) {
                    ackApp();
                }
            }
            maybeClose();
        }

        private void startWriter() {
            if (writing || closed) return;
            writing = true;
            if (!spawn(() -> writer(this), "fw-tcp-w", tcpThreads)) abort();
        }

        private void ackApp() { if (!closed) emitTcp(this, ourSeq, appNext, ACK, null, 0); }

        /** The receive window we offer: the space left for data waiting on the server. */
        int offer() {
            advertised = Math.max(0, Math.min(WINDOW, UP_BUFFER - queued));
            return advertised;
        }

        /** Bytes we may send to the app now. One byte probes a closed window. */
        int room() {
            int flight = ourSeq - ackedSeq;
            int limit = Math.min(WINDOW, peerWindow);
            if (limit == 0 && flight == 0) limit = 1;
            return limit - flight;
        }

        /** Remote data read on the reader thread. */
        synchronized void sendData(byte[] d) {
            if (closed) return;
            Seg s = new Seg(ourSeq, PSH, d);
            unacked.add(s);
            ourSeq += d.length;
            last = System.currentTimeMillis();
            transmit(s);
            if (longSleep) wake();
        }

        synchronized void sendFin() {
            if (closed || finSent) return;
            finSent = true;
            Seg s = new Seg(ourSeq, FIN, null);
            unacked.add(s);
            ourSeq += 1;
            transmit(s);
            if (longSleep) wake();
        }

        private void transmit(Seg s) {
            emitTcp(this, s.seq, appNext, s.flags | ACK, s.data, s.len);
            lastSend = System.currentTimeMillis();
        }

        /**
         * Periodic check: retransmit a stalled segment, give up on a dead flow, drop an idle one.
         * Returns true while something is waiting to be acknowledged.
         */
        synchronized boolean tick(long now) {
            if (closed) return false;
            if (now - last > TCP_IDLE_MS) { abort(); return false; }
            if (!unacked.isEmpty() && now - lastSend > RTO_MS) {
                if (peerWindow > 0 && ++rtoTries > RTO_TRIES) { abort(); return false; }
                transmit(unacked.peekFirst());
            }
            return !unacked.isEmpty();
        }

        /** Done once both sides have finished and everything has been delivered. */
        synchronized void maybeClose() {
            if (appFin && eofWritten && finSent && unacked.isEmpty()) close();
        }

        synchronized void abort() {
            if (closed) return;
            if (connected) emitRst(rip, rport, aport, ourSeq, appNext, true);
            close();
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            tcps.remove(key, this);
            toRemote.clear();
            toRemote.add(EOF);   // wakes the writer
            notifyAll();
            Socket s = sock;
            if (s != null) try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void tcpPacket(byte[] p, int off, int total, int dst) {
        if (total - off < 20) { dropped(); return; }
        int aport = u16(p, off), rport = u16(p, off + 2);
        int seq = i32(p, off + 4), ack = i32(p, off + 8);
        int hl = ((p[off + 12] >> 4) & 0xf) * 4;
        int flags = p[off + 13] & 0xff;
        int window = u16(p, off + 14);   // unscaled: we never offer window scaling
        if (hl < 20 || off + hl > total) { dropped(); return; }
        int dl = total - off - hl;
        String key = "t" + aport + ":" + dst + ":" + rport;
        boolean syn = (flags & (SYN | ACK | RST)) == SYN;
        Tcp c = tcps.get(key);
        if (c != null && syn) {
            synchronized (c) {
                if (c.synSeq == seq) {   // our answer got lost: send it again
                    if (c.connected && !c.closed) emitSynAck(c);
                    return;
                }
                c.close();               // the app reused the port for a new connection
                c = null;
            }
        }
        if (c != null) {
            c.onPacket(p, off + hl, dl, seq, ack, flags, window);
        } else if (syn) {
            open(new Tcp(key, dst, rport, aport, seq, mss(p, off + 20, off + hl), window));
        } else if ((flags & RST) == 0) {
            // no such flow (closed, or never existed): tell the app to give up on it
            if ((flags & ACK) != 0) emitRst(dst, rport, aport, ack, 0, false);
            else emitRst(dst, rport, aport, 0, seq + dl + ((flags & (SYN | FIN)) != 0 ? 1 : 0), true);
        }
    }

    /** The MSS option of the app's SYN, limited to what fits the tunnel. */
    private static int mss(byte[] p, int i, int end) {
        int mss = 536;
        while (i < end) {
            int kind = p[i] & 0xff;
            if (kind == 0) break;
            if (kind == 1) { i++; continue; }
            if (i + 1 >= end) break;
            int len = p[i + 1] & 0xff;
            if (len < 2 || i + len > end) break;
            if (kind == 2 && len == 4) mss = u16(p, i + 2);
            i += len;
        }
        return Math.max(88, Math.min(MTU - 40, mss));
    }

    private void open(Tcp c) {
        if (tcps.size() >= MAX_TCP) {   // full: drop a long-quiet flow, if there is one
            Tcp oldest = null;
            for (Tcp x : tcps.values()) if (oldest == null || x.last < oldest.last) oldest = x;
            if (oldest != null && System.currentTimeMillis() - oldest.last > TCP_EVICT_MS) oldest.abort();
        }
        if (tcps.size() >= MAX_TCP || tcpThreads.get() >= 3 * MAX_TCP) { refuse(c); return; }
        tcps.put(c.key, c);
        if (!spawn(() -> run(c), "fw-tcp-r", tcpThreads)) { refuse(c); c.close(); return; }
        if (stopped) c.close();
        else wake();
    }

    private void refuse(Tcp c) {
        dropped();
        emitRst(c.rip, c.rport, c.aport, 0, c.appNext, true);
    }

    /** Opens the real connection, answers the app's SYN, then carries internet -> app. */
    private void run(Tcp c) {
        Socket s = new Socket();
        try {
            s.bind(new InetSocketAddress(0));   // gives the socket a file descriptor so it can be protected
            if (!svc.protect(s)) throw new IOException("protect failed");
            s.setTcpNoDelay(true);
            synchronized (c) {
                if (c.closed) { s.close(); return; }
                c.sock = s;                     // from here close() also cancels a pending connect
            }
            s.connect(new InetSocketAddress(addr(c.rip), c.rport), 10000);
            synchronized (c) {
                if (c.closed) return;
                c.ourSeq = c.isn + 1;
                c.ackedSeq = c.ourSeq;
                c.connected = true;
                c.last = System.currentTimeMillis();
                emitSynAck(c);
            }
            reader(c);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            synchronized (c) {
                if (c.connected) c.abort();
                else if (!c.closed) emitRst(c.rip, c.rport, c.aport, 0, c.appNext, true);
                c.close();
            }
            try { s.close(); } catch (IOException ignored) { }
        } catch (InterruptedException ignored) {
            c.close();
        }
    }

    /** App data -> internet. Runs on its own thread so one slow server can't stall the tunnel. */
    private void writer(Tcp c) {
        try {
            OutputStream o = c.sock.getOutputStream();
            while (true) {
                byte[] b = c.toRemote.take();
                if (c.closed) return;
                if (b == EOF) {
                    c.sock.shutdownOutput();
                    synchronized (c) {
                        c.eofWritten = true;
                        c.maybeClose();
                    }
                    return;
                }
                o.write(b);
                synchronized (c) {
                    c.queued -= b.length;
                    // the app was told to slow down; tell it there is room again
                    if (c.advertised < MTU && UP_BUFFER - c.queued >= UP_BUFFER / 4) c.ackApp();
                }
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            c.abort();
        } catch (InterruptedException ignored) {
            c.close();
        }
    }

    /** Internet -> app, never more than the app has room for. */
    private void reader(Tcp c) throws IOException, InterruptedException {
        byte[] buf = new byte[c.mss];
        InputStream i = c.sock.getInputStream();
        while (true) {
            int room;
            synchronized (c) {
                while (!c.closed && c.room() <= 0) c.wait();
                if (c.closed) return;
                room = c.room();
            }
            int n = i.read(buf, 0, Math.min(room, buf.length));
            if (n < 0) { c.sendFin(); return; }
            if (n > 0) c.sendData(Arrays.copyOf(buf, n));
        }
    }

    // ---- packet building ------------------------------------------------------------------------

    private void emitTcp(Tcp c, int seq, int ack, int flags, byte[] d, int len) {
        int tot = 40 + len;
        byte[] pk = new byte[tot];
        ipHeader(pk, 6, c.rip, LOCAL, tot);
        put16(pk, 20, c.rport);
        put16(pk, 22, c.aport);
        put32(pk, 24, seq);
        put32(pk, 28, ack);
        pk[32] = 5 << 4;
        pk[33] = (byte) flags;
        put16(pk, 34, c.offer());
        if (len > 0) System.arraycopy(d, 0, pk, 40, len);
        put16(pk, 36, transportSum(pk, 20, 20 + len, 6, c.rip, LOCAL));
        emit(pk);
    }

    private void emitSynAck(Tcp c) {
        byte[] pk = new byte[44];
        ipHeader(pk, 6, c.rip, LOCAL, 44);
        put16(pk, 20, c.rport);
        put16(pk, 22, c.aport);
        put32(pk, 24, c.isn);
        put32(pk, 28, c.synSeq + 1);
        pk[32] = 6 << 4;                       // 24-byte header: includes the MSS option
        pk[33] = (byte) (SYN | ACK);
        put16(pk, 34, WINDOW);
        pk[40] = 2;                            // option kind: MSS
        pk[41] = 4;
        put16(pk, 42, MTU - 40);
        put16(pk, 36, transportSum(pk, 20, 24, 6, c.rip, LOCAL));
        emit(pk);
    }

    private void emitRst(int rip, int rport, int aport, int seq, int ack, boolean withAck) {
        byte[] pk = new byte[40];
        ipHeader(pk, 6, rip, LOCAL, 40);
        put16(pk, 20, rport);
        put16(pk, 22, aport);
        put32(pk, 24, seq);
        put32(pk, 28, ack);
        pk[32] = 5 << 4;
        pk[33] = (byte) (RST | (withAck ? ACK : 0));
        put16(pk, 36, transportSum(pk, 20, 20, 6, rip, LOCAL));
        emit(pk);
    }

    private void emit(byte[] pk) {
        if (stopped) return;
        try {
            synchronized (out) { out.write(pk); }
        } catch (IOException ignored) {
            // tunnel gone; the loop will notice
        }
    }

    private static void ipHeader(byte[] p, int proto, int src, int dst, int total) {
        p[0] = 0x45;
        p[1] = 0;
        put16(p, 2, total);
        put16(p, 4, IP_ID.getAndIncrement() & 0xffff);
        put16(p, 6, 0x4000);                   // don't fragment
        p[8] = 64;
        p[9] = (byte) proto;
        put16(p, 10, 0);
        put32(p, 12, src);
        put32(p, 16, dst);
        put16(p, 10, fold(sum(p, 0, 20, 0)));
    }

    /** Checksum over the TCP/UDP header and data, with the IPv4 pseudo-header. */
    private static int transportSum(byte[] b, int off, int len, int proto, int src, int dst) {
        long acc = ((src >>> 16) & 0xffff) + (src & 0xffff)
                + ((dst >>> 16) & 0xffff) + (dst & 0xffff) + proto + len;
        return fold(sum(b, off, len, acc));
    }

    private static long sum(byte[] b, int off, int len, long acc) {
        int i = 0;
        for (; i + 1 < len; i += 2) acc += ((b[off + i] & 0xff) << 8) | (b[off + i + 1] & 0xff);
        if (i < len) acc += (b[off + i] & 0xff) << 8;
        return acc;
    }

    private static int fold(long s) {
        while ((s >>> 16) != 0) s = (s & 0xffff) + (s >>> 16);
        return (int) (~s & 0xffff);
    }

    // ---- small helpers --------------------------------------------------------------------------

    private static int ip(int a, int b, int c, int d) { return (a << 24) | (b << 16) | (c << 8) | d; }

    private static byte[] bytes(int ip) {
        return new byte[]{(byte) (ip >>> 24), (byte) (ip >>> 16), (byte) (ip >>> 8), (byte) ip};
    }

    private static InetAddress addr(int ip) throws UnknownHostException {
        return InetAddress.getByAddress(bytes(ip));
    }

    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }

    private static int i32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static void put16(byte[] b, int o, int v) { b[o] = (byte) (v >>> 8); b[o + 1] = (byte) v; }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16); b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v;
    }
}
