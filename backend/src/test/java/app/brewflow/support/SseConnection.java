package app.brewflow.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Raw-socket SSE reader, so a test controls exactly when the client disconnects.
 * Lines are queued; {@link #END} marks end of stream.
 */
public class SseConnection implements AutoCloseable {

    public static final String END = "<<END>>";

    private final Socket socket;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final String statusLine;

    public SseConnection(int port, String sessionCookie) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout((int) Duration.ofSeconds(30).toMillis());
        OutputStream out = socket.getOutputStream();
        out.write(("GET /api/v1/kds/stream HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAccept: text/event-stream\r\n"
                + "Connection: close\r\nCookie: BFSESSION=" + sessionCookie + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        statusLine = reader.readLine();
        String header;
        while ((header = reader.readLine()) != null && !header.isEmpty()) {
            // skip headers
        }
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // socket closed
            }
            lines.add(END);
        }, "sse-reader");
        t.setDaemon(true);
        t.start();
    }

    public String statusLine() {
        return statusLine;
    }

    /** Next line containing the fragment, or null on timeout or end of stream. */
    public String awaitLineContaining(String fragment, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String line = lines.poll(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
            if (line == null || line.equals(END)) {
                return null;
            }
            if (line.contains(fragment)) {
                return line;
            }
        }
        return null;
    }

    /** True when the server ended the stream within the timeout. */
    public boolean awaitEnd(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String line = lines.poll(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
            if (END.equals(line)) {
                return true;
            }
            // chunked encoding emits "0" as the terminating chunk before the connection idles
            if ("0".equals(line)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
