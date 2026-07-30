package dev.sealedclient.v26.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A loopback TCP proxy that adds a fixed one-way delay to every byte it
 * forwards, so a local dedicated server can be made to behave like a distant,
 * laggy one.
 *
 * <p>This is how the suite reproduces 2b2t-style latency without a remote
 * server: the client connects to the proxy, the proxy connects to the real
 * server, and each direction is held back by {@code delayMillis}. Round-trip
 * ping therefore lands near {@code 2 * delayMillis}, which is what the client's
 * ping readout and its latency-sensitive movement safety policy observe.</p>
 *
 * <p>Delay is applied by timestamping each chunk on arrival and releasing it
 * once the deadline passes. Byte order within a direction is preserved, so this
 * changes timing only — never packet ordering or content.</p>
 *
 * <p>Test-only: this class lives in the gametest source set and is never
 * shipped in the mod JAR.</p>
 */
public final class LatencyProxy26 implements AutoCloseable {
    private final ServerSocket listener;
    private final String targetHost;
    private final int targetPort;
    private final long delayMillis;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong forwardedBytes = new AtomicLong();
    private final Thread acceptor;

    private LatencyProxy26(
            ServerSocket listener,
            String targetHost,
            int targetPort,
            long delayMillis
    ) {
        this.listener = listener;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.delayMillis = delayMillis;
        this.acceptor = new Thread(this::acceptLoop, "b2t-latency-proxy");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    /**
     * Starts a proxy on an ephemeral loopback port.
     *
     * @param delayMillis one-way delay; round-trip latency is roughly twice this
     */
    public static LatencyProxy26 start(
            String targetHost,
            int targetPort,
            long delayMillis
    ) throws IOException {
        if (delayMillis < 0) {
            throw new IllegalArgumentException("Delay must not be negative");
        }
        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        return new LatencyProxy26(listener, targetHost, targetPort, delayMillis);
    }

    /** The address the client should connect to, as {@code host:port}. */
    public String address() {
        return InetAddress.getLoopbackAddress().getHostAddress()
                + ":" + listener.getLocalPort();
    }

    public long forwardedBytes() {
        return forwardedBytes.get();
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket downstream = null;
            Socket upstream = null;
            try {
                downstream = listener.accept();
                upstream = new Socket(targetHost, targetPort);
                downstream.setTcpNoDelay(true);
                upstream.setTcpNoDelay(true);
                pump(downstream, upstream);
                pump(upstream, downstream);
            } catch (IOException exception) {
                closeQuietly(downstream);
                closeQuietly(upstream);
                if (running.get() && !listener.isClosed()) {
                    continue;
                }
                return;
            }
        }
    }

    private void pump(Socket from, Socket to) {
        Thread worker = new Thread(() -> {
            Deque<Delayed> queue = new ArrayDeque<>();
            byte[] buffer = new byte[16 * 1024];
            try (InputStream in = from.getInputStream();
                 OutputStream out = to.getOutputStream()) {
                while (running.get()) {
                    // Release everything whose delay has elapsed before blocking
                    // on the next read, so a quiet direction still drains.
                    drain(queue, out);
                    if (in.available() == 0 && !queue.isEmpty()) {
                        Thread.sleep(1);
                        continue;
                    }
                    int read = in.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read > 0) {
                        byte[] copy = new byte[read];
                        System.arraycopy(buffer, 0, copy, 0, read);
                        queue.addLast(new Delayed(
                                System.nanoTime() + delayMillis * 1_000_000L,
                                copy
                        ));
                    }
                }
                // Flush whatever is still held back before tearing down.
                while (!queue.isEmpty()) {
                    drain(queue, out);
                    Thread.sleep(1);
                }
            } catch (IOException | InterruptedException ignored) {
                // Either side closing ends this direction; the peer thread and
                // the accept loop handle their own teardown.
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }, "b2t-latency-pump");
        worker.setDaemon(true);
        worker.start();
    }

    private void drain(Deque<Delayed> queue, OutputStream out) throws IOException {
        long now = System.nanoTime();
        while (!queue.isEmpty() && queue.peekFirst().releaseAtNanos() <= now) {
            byte[] payload = queue.removeFirst().payload();
            out.write(payload);
            forwardedBytes.addAndGet(payload.length);
        }
        out.flush();
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Teardown is best effort.
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            listener.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        acceptor.interrupt();
    }

    private record Delayed(long releaseAtNanos, byte[] payload) {
    }
}
