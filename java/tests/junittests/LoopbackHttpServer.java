// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class LoopbackHttpServer implements AutoCloseable {
    private static final long CALLBACK_TIMEOUT_SECONDS = 20;
    private final int expectedRequestCount_;
    private final ResponseHandler handler_;
    private final ServerSocket serverSocket_ = new ServerSocket();
    private final CountDownLatch responsesFinished_;
    // Chromium may open idle speculative connections. Keep TCP accepts diagnostic-only and count
    // semantic requests after a complete valid request line and header block have been parsed.
    private final AtomicInteger acceptedConnections_ = new AtomicInteger();
    private final AtomicInteger receivedRequests_ = new AtomicInteger();
    private final List<String> receivedRequestLines_ = new CopyOnWriteArrayList<String>();
    private final AtomicReference<Throwable> failure_ = new AtomicReference<Throwable>();
    private final ExecutorService acceptor_ = Executors.newSingleThreadExecutor(daemonThreads("url-request-accept"));
    private final ExecutorService connections_;
    // Socket registration and the closing transition must remain under the same lock. This makes
    // every accept/close interleaving deterministic: close() either owns the accepted socket in
    // its snapshot, or the acceptor observes closing_ and closes the socket itself before dispatch.
    private final Object lifecycleLock_ = new Object();
    private final Set<Socket> activeSockets_ = new HashSet<Socket>();
    private boolean closing_;

    @FunctionalInterface
    interface ResponseHandler {
        void handle(String requestLine, OutputStream output) throws Exception;
    }

    LoopbackHttpServer(int requestCount, ResponseHandler handler) throws IOException {
        if (requestCount <= 0) throw new IllegalArgumentException("requestCount must be positive");
        expectedRequestCount_ = requestCount;
        handler_ = handler;
        responsesFinished_ = new CountDownLatch(requestCount);
        connections_ = Executors.newCachedThreadPool(daemonThreads("url-request-connection"));
        start();
    }

    private void start() throws IOException {
        serverSocket_.setReuseAddress(true);
        serverSocket_.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        acceptor_.execute(this::acceptRequests);
    }

    String url(String path) {
        return "http://127.0.0.1:" + serverSocket_.getLocalPort() + path;
    }

    void awaitHealthy() throws Exception {
        assertTrue(responsesFinished_.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Timed out waiting for the loopback HTTP responses");
        assertHealthy();
        assertEquals(expectedRequestCount_, receivedRequests_.get(), "Unexpected loopback HTTP request count: " + diagnostics());
    }

    int receivedRequestCount() {
        return receivedRequests_.get();
    }

    String diagnostics() {
        return "accepted connections=" + acceptedConnections_.get() + ", received requests=" + receivedRequestLines_;
    }

    void awaitReceivedRequestCount(int expectedCount) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CALLBACK_TIMEOUT_SECONDS);
        synchronized (receivedRequests_) {
            while (receivedRequests_.get() < expectedCount && failure_.get() == null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                TimeUnit.NANOSECONDS.timedWait(receivedRequests_, remaining);
            }
        }
        assertEquals(expectedCount, receivedRequests_.get(), "Unexpected loopback HTTP request count: " + diagnostics());
        assertHealthy();
    }

    void assertHealthy() {
        Throwable throwable = failure_.get();
        if (throwable != null) throw new AssertionError("Loopback HTTP server failed", throwable);
    }

    static void writeResponse(OutputStream output, String status, String extraHeaders, byte[] body) throws IOException {
        byte[] headers = ("HTTP/1.1 " + status + "\r\n" + extraHeaders + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        output.write(headers);
        output.write(body);
        output.flush();
    }

    private void acceptRequests() {
        try {
            while (true) {
                Socket socket = serverSocket_.accept();
                synchronized (lifecycleLock_) {
                    if (closing_) {
                        closeSocket(socket);
                        return;
                    }
                    activeSockets_.add(socket);
                }
                acceptedConnections_.incrementAndGet();
                try {
                    connections_.execute(() -> handleRequest(socket));
                } catch (RejectedExecutionException exception) {
                    closeAndUnregisterSocket(socket);
                    if (!isClosing()) throw exception;
                    return;
                }
            }
        } catch (Throwable throwable) {
            recordFailure(throwable);
        }
    }

    private void handleRequest(Socket socket) {
        boolean requestReceived = false;
        try (Socket connection = socket; BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII))) {
            connection.setSoTimeout((int) TimeUnit.SECONDS.toMillis(CALLBACK_TIMEOUT_SECONDS));
            String requestLine = reader.readLine();
            // Chromium may speculatively connect without sending an HTTP request. A clean EOF on
            // such a socket is transport activity, not a request or a server failure.
            if (requestLine == null) return;
            if (!requestLine.startsWith("GET /")) throw new IOException("Unexpected request line: " + requestLine);
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
            }
            if (line == null) throw new IOException("Unexpected EOF in request headers: " + requestLine);
            receivedRequestLines_.add(requestLine);
            int receivedRequestCount;
            synchronized (receivedRequests_) {
                receivedRequestCount = receivedRequests_.incrementAndGet();
                receivedRequests_.notifyAll();
            }
            requestReceived = true;
            if (receivedRequestCount > expectedRequestCount_) throw new IOException("Received more HTTP requests than expected: " + diagnostics());
            handler_.handle(requestLine, connection.getOutputStream());
        } catch (Throwable throwable) {
            recordFailure(throwable);
        } finally {
            synchronized (lifecycleLock_) {
                activeSockets_.remove(socket);
            }
            if (requestReceived) responsesFinished_.countDown();
        }
    }

    private void recordFailure(Throwable throwable) {
        if (isClosing()) return;
        failure_.compareAndSet(null, throwable);
        while (responsesFinished_.getCount() != 0) responsesFinished_.countDown();
        synchronized (receivedRequests_) {
            receivedRequests_.notifyAll();
        }
    }

    private boolean isClosing() {
        synchronized (lifecycleLock_) {
            return closing_;
        }
    }

    private void closeAndUnregisterSocket(Socket socket) {
        closeSocket(socket);
        synchronized (lifecycleLock_) {
            activeSockets_.remove(socket);
        }
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void awaitTermination(long deadlineNanos, ExecutorService... executors) {
        boolean interrupted = false;
        try {
            for (ExecutorService executor : executors) {
                while (!executor.isTerminated()) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) throw new IllegalStateException("Timed out shutting down the loopback HTTP server");
                    try {
                        executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        Socket[] activeSockets;
        synchronized (lifecycleLock_) {
            closing_ = true;
            activeSockets = activeSockets_.toArray(new Socket[0]);
        }
        try {
            serverSocket_.close();
        } catch (IOException ignored) {
        }
        for (Socket socket : activeSockets) closeSocket(socket);
        acceptor_.shutdownNow();
        connections_.shutdownNow();
        awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(CALLBACK_TIMEOUT_SECONDS), acceptor_, connections_);
        assertHealthy();
        assertEquals(expectedRequestCount_, receivedRequests_.get(), "Unexpected loopback HTTP request count during close: " + diagnostics());
    }
}
