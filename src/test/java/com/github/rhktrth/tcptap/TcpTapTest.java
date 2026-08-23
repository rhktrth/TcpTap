package com.github.rhktrth.tcptap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class TcpTapTest {
    private static final int ENHANCED_PACKET_BLOCK = 0x00000006;

    @Test
    void relaysBothDirectionsAndPropagatesHalfClose() throws Exception {
        try (ServerSocket clientSideListener = new ServerSocket(0);
                ServerSocket destinationListener = new ServerSocket(0);
                Socket client = new Socket("127.0.0.1", clientSideListener.getLocalPort());
                Socket relayClient = clientSideListener.accept();
                Socket relayDestination = new Socket("127.0.0.1", destinationListener.getLocalPort());
                Socket destination = destinationListener.accept()) {

            client.setSoTimeout(2000);
            destination.setSoTimeout(2000);

            ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(
                    outputBytes, true, StandardCharsets.UTF_8.name());
            Thread tap = new Thread(new TcpTap(
                    1,
                    relayClient,
                    relayDestination,
                    System.nanoTime(),
                    out,
                    null));
            tap.start();

            byte[] request = "hello".getBytes(StandardCharsets.UTF_8);
            client.getOutputStream().write(request);
            client.getOutputStream().flush();
            assertArrayEquals(request, readFully(destination.getInputStream(), request.length));

            byte[] response = "world".getBytes(StandardCharsets.UTF_8);
            destination.getOutputStream().write(response);
            destination.getOutputStream().flush();
            assertArrayEquals(response, readFully(client.getInputStream(), response.length));

            client.shutdownOutput();
            assertEquals(-1, destination.getInputStream().read());

            destination.shutdownOutput();
            assertEquals(-1, client.getInputStream().read());

            tap.join(2000);
            assertFalse(tap.isAlive());

            String output = outputBytes.toString(StandardCharsets.UTF_8.name());
            assertTrue(output.contains("#000001 CLOSE"));
            assertTrue(output.contains("c2d=5B"));
            assertTrue(output.contains("d2c=5B"));
            assertTrue(output.contains("c2d_end=EOF"));
            assertTrue(output.contains("d2c_end=EOF"));
        }
    }

    @Test
    void capturesBytesObservedByTcpTap() throws Exception {
        Path directory = Files.createTempDirectory("tcptap-observed-capture-");
        Path captureFile = directory.resolve("session.pcapng");
        byte[] request = "captured-client-data".getBytes(StandardCharsets.UTF_8);
        byte[] response = "captured-server-data".getBytes(StandardCharsets.UTF_8);

        try (ServerSocket clientSideListener = new ServerSocket(0);
                ServerSocket destinationListener = new ServerSocket(0);
                Socket client = new Socket("127.0.0.1", clientSideListener.getLocalPort());
                Socket relayClient = clientSideListener.accept();
                Socket relayDestination = new Socket("127.0.0.1", destinationListener.getLocalPort());
                Socket destination = destinationListener.accept();
                PcapNgWriter writer = new PcapNgWriter(captureFile.toFile())) {

            client.setSoTimeout(2000);
            destination.setSoTimeout(2000);

            PcapNgWriter.SessionCapture capture = writer.startSession(21, relayClient, relayDestination);
            ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(
                    outputBytes, true, StandardCharsets.UTF_8.name());
            Thread tap = new Thread(new TcpTap(
                    21,
                    relayClient,
                    relayDestination,
                    System.nanoTime(),
                    out,
                    capture));
            tap.start();

            client.getOutputStream().write(request);
            client.getOutputStream().flush();
            assertArrayEquals(request, readFully(destination.getInputStream(), request.length));

            destination.getOutputStream().write(response);
            destination.getOutputStream().flush();
            assertArrayEquals(response, readFully(client.getInputStream(), response.length));

            client.shutdownOutput();
            assertEquals(-1, destination.getInputStream().read());
            destination.shutdownOutput();
            assertEquals(-1, client.getInputStream().read());

            tap.join(2000);
            assertFalse(tap.isAlive());

            String output = outputBytes.toString(StandardCharsets.UTF_8.name());
            assertTrue(output.contains("c2d=" + request.length + "B"));
            assertTrue(output.contains("d2c=" + response.length + "B"));
        }

        byte[] capture = Files.readAllBytes(captureFile);
        assertTrue(captureContainsPayload(capture, request));
        assertTrue(captureContainsPayload(capture, response));

        Files.deleteIfExists(captureFile);
        Files.deleteIfExists(directory);
    }

    @Test
    void capturesBytesObservedBeforeRelayWriteFails() throws Exception {
        Path directory = Files.createTempDirectory("tcptap-observed-write-failure-");
        Path captureFile = directory.resolve("session.pcapng");
        byte[] observed = "observed-before-write-failure".getBytes(StandardCharsets.UTF_8);
        StubSocket clientSocket = new StubSocket(
                new ByteArrayInputStream(observed),
                new ByteArrayOutputStream(),
                new InetSocketAddress("127.0.0.1", 34001));
        StubSocket destinationSocket = new StubSocket(
                new ByteArrayInputStream(new byte[0]),
                new FailingOutputStream(),
                new InetSocketAddress("127.0.0.1", 34002));
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();

        try (PcapNgWriter writer = new PcapNgWriter(captureFile.toFile())) {
            PcapNgWriter.SessionCapture capture = writer.startSession(
                    61, clientSocket, destinationSocket);
            PrintStream out = new PrintStream(
                    outputBytes, true, StandardCharsets.UTF_8.name());

            new TcpTap(
                    61,
                    clientSocket,
                    destinationSocket,
                    System.nanoTime(),
                    out,
                    capture).run();
        }

        String output = outputBytes.toString(StandardCharsets.UTF_8.name());
        assertTrue(output.contains("#000061 C->D ERROR IOException"));
        assertTrue(output.contains("c2d=0B"));
        assertTrue(output.contains("c2d_end=IO_ERROR:IOException"));

        byte[] capture = Files.readAllBytes(captureFile);
        assertTrue(captureContainsPayload(capture, observed));
        assertTrue(captureContainsFlags(capture, 0x14));

        Files.deleteIfExists(captureFile);
        Files.deleteIfExists(directory);
    }

    @Test
    void captureWriteFailureDoesNotBreakRelay() throws Exception {
        Path directory = Files.createTempDirectory("tcptap-capture-failure-");
        Path captureFile = directory.resolve("session.pcapng");
        byte[] request = "relay-survives-capture-failure".getBytes(StandardCharsets.UTF_8);
        byte[] response = "response-still-relayed".getBytes(StandardCharsets.UTF_8);

        PcapNgWriter writer = new PcapNgWriter(captureFile.toFile());
        writer.close();
        long initializedCaptureSize = Files.size(captureFile);

        try (ServerSocket clientSideListener = new ServerSocket(0);
                ServerSocket destinationListener = new ServerSocket(0);
                Socket client = new Socket("127.0.0.1", clientSideListener.getLocalPort());
                Socket relayClient = clientSideListener.accept();
                Socket relayDestination = new Socket("127.0.0.1", destinationListener.getLocalPort());
                Socket destination = destinationListener.accept()) {

            client.setSoTimeout(2000);
            destination.setSoTimeout(2000);

            // A closed writer deterministically makes the next capture flush fail without
            // relying on platform-specific disk-full or filesystem fault injection.
            PcapNgWriter.SessionCapture capture = writer.startSession(41, relayClient, relayDestination);
            ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(
                    outputBytes, true, StandardCharsets.UTF_8.name());
            Thread tap = new Thread(new TcpTap(
                    41,
                    relayClient,
                    relayDestination,
                    System.nanoTime(),
                    out,
                    capture));
            tap.start();

            client.getOutputStream().write(request);
            client.getOutputStream().flush();
            assertArrayEquals(request, readFully(destination.getInputStream(), request.length));

            destination.getOutputStream().write(response);
            destination.getOutputStream().flush();
            assertArrayEquals(response, readFully(client.getInputStream(), response.length));

            client.shutdownOutput();
            assertEquals(-1, destination.getInputStream().read());
            destination.shutdownOutput();
            assertEquals(-1, client.getInputStream().read());

            tap.join(2000);
            assertFalse(tap.isAlive());

            String output = outputBytes.toString(StandardCharsets.UTF_8.name());
            assertTrue(output.contains("#000041 CLOSE"));
            assertTrue(output.contains("c2d=" + request.length + "B"));
            assertTrue(output.contains("d2c=" + response.length + "B"));
            assertTrue(output.contains("c2d_end=EOF"));
            assertTrue(output.contains("d2c_end=EOF"));
        }

        assertEquals(initializedCaptureSize, Files.size(captureFile));
        Files.deleteIfExists(captureFile);
        Files.deleteIfExists(directory);
    }

    @Test
    void relayIoErrorTerminatesSessionAndRecordsReset() throws Exception {
        Path directory = Files.createTempDirectory("tcptap-relay-error-");
        Path captureFile = directory.resolve("session.pcapng");
        StubSocket clientSocket = new StubSocket(
                new FailingInputStream(),
                new ByteArrayOutputStream(),
                new InetSocketAddress("127.0.0.1", 32001));
        StubSocket destinationSocket = new StubSocket(
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                new InetSocketAddress("127.0.0.1", 32002));
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();

        try (PcapNgWriter writer = new PcapNgWriter(captureFile.toFile())) {
            PcapNgWriter.SessionCapture capture = writer.startSession(
                    31, clientSocket, destinationSocket);
            PrintStream out = new PrintStream(
                    outputBytes, true, StandardCharsets.UTF_8.name());

            new TcpTap(
                    31,
                    clientSocket,
                    destinationSocket,
                    System.nanoTime(),
                    out,
                    capture).run();
        }

        String output = outputBytes.toString(StandardCharsets.UTF_8.name());
        assertTrue(output.contains("#000031 C->D ERROR IOException"));
        assertTrue(output.contains("c2d_end=IO_ERROR:IOException"));
        assertTrue(output.contains("d2c_end=EOF"));
        assertTrue(clientSocket.isClosedByTest());
        assertTrue(destinationSocket.isClosedByTest());
        assertTrue(captureContainsFlags(Files.readAllBytes(captureFile), 0x14));

        Files.deleteIfExists(captureFile);
        Files.deleteIfExists(directory);
    }

    @Test
    void interruptedSessionWaitsForRelayThreadsBeforeCloseSummary() throws Exception {
        BlockingInputStream clientInput = new BlockingInputStream();
        BlockingInputStream destinationInput = new BlockingInputStream();
        BlockingSocket clientSocket = new BlockingSocket(
                clientInput, new InetSocketAddress("127.0.0.1", 33001));
        BlockingSocket destinationSocket = new BlockingSocket(
                destinationInput, new InetSocketAddress("127.0.0.1", 33002));
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outputBytes, true, StandardCharsets.UTF_8.name());
        Thread tap = new Thread(new TcpTap(
                51,
                clientSocket,
                destinationSocket,
                System.nanoTime(),
                out,
                null));
        tap.start();

        assertTrue(clientInput.awaitRead());
        assertTrue(destinationInput.awaitRead());
        tap.interrupt();
        assertTrue(clientSocket.awaitClosed());
        assertTrue(destinationSocket.awaitClosed());
        boolean waitedForRelayThreads = tap.isAlive();

        clientInput.releaseRead();
        destinationInput.releaseRead();
        tap.join(2000);

        assertTrue(waitedForRelayThreads);
        assertFalse(tap.isAlive());
        String output = outputBytes.toString(StandardCharsets.UTF_8.name());
        assertTrue(output.contains("#000051 CLOSE"));
        assertFalse(output.contains("UNKNOWN"));
    }

    private static boolean captureContainsPayload(byte[] file, byte[] expectedPayload) {
        int offset = 0;
        while (offset < file.length) {
            int blockType = readIntLE(file, offset);
            int blockLength = readIntLE(file, offset + 4);
            if (blockType == ENHANCED_PACKET_BLOCK && readIntLE(file, offset + 8) == 0) {
                int capturedLength = readIntLE(file, offset + 20);
                int packetOffset = offset + 28;
                int version = (file[packetOffset] >>> 4) & 0x0f;
                int tcpOffset = packetOffset + (version == 6
                        ? 40
                        : (file[packetOffset] & 0x0f) * 4);
                int tcpHeaderLength = ((file[tcpOffset + 12] >>> 4) & 0x0f) * 4;
                int payloadOffset = tcpOffset + tcpHeaderLength;
                int payloadLength = packetOffset + capturedLength - payloadOffset;
                if (payloadLength == expectedPayload.length) {
                    boolean equal = true;
                    for (int i = 0; i < payloadLength; i++) {
                        if (file[payloadOffset + i] != expectedPayload[i]) {
                            equal = false;
                            break;
                        }
                    }
                    if (equal) {
                        return true;
                    }
                }
            }
            offset += blockLength;
        }
        return false;
    }

    private static boolean captureContainsFlags(byte[] file, int expectedFlags) {
        int offset = 0;
        while (offset < file.length) {
            int blockType = readIntLE(file, offset);
            int blockLength = readIntLE(file, offset + 4);
            if (blockType == ENHANCED_PACKET_BLOCK && readIntLE(file, offset + 8) == 0) {
                int packetOffset = offset + 28;
                int version = (file[packetOffset] >>> 4) & 0x0f;
                int tcpOffset = packetOffset + (version == 6
                        ? 40
                        : (file[packetOffset] & 0x0f) * 4);
                if ((file[tcpOffset + 13] & 0xff) == expectedFlags) {
                    return true;
                }
            }
            offset += blockLength;
        }
        return false;
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) {
                throw new IOException("unexpected EOF");
            }
            offset += count;
        }
        return result;
    }

    private static final class FailingInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("forced read failure");
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            throw new IOException("forced write failure");
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch readRelease = new CountDownLatch(1);
        private volatile boolean closed;

        @Override
        public int read() throws IOException {
            readStarted.countDown();
            try {
                readRelease.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting", e);
            }
            if (closed) {
                throw new IOException("socket closed");
            }
            return -1;
        }

        private boolean awaitRead() throws InterruptedException {
            return readStarted.await(2, TimeUnit.SECONDS);
        }

        private void markClosed() {
            closed = true;
        }

        private void releaseRead() {
            readRelease.countDown();
        }
    }

    private static final class BlockingSocket extends Socket {
        private final BlockingInputStream input;
        private final OutputStream output = new ByteArrayOutputStream();
        private final SocketAddress remoteAddress;
        private final CountDownLatch closed = new CountDownLatch(1);
        private boolean outputShutdown;

        private BlockingSocket(BlockingInputStream input, SocketAddress remoteAddress) {
            this.input = input;
            this.remoteAddress = remoteAddress;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return remoteAddress;
        }

        @Override
        public void shutdownOutput() {
            outputShutdown = true;
        }

        @Override
        public boolean isOutputShutdown() {
            return outputShutdown;
        }

        @Override
        public synchronized void close() {
            input.markClosed();
            closed.countDown();
        }

        private boolean awaitClosed() throws InterruptedException {
            return closed.await(2, TimeUnit.SECONDS);
        }
    }

    private static final class StubSocket extends Socket {
        private final InputStream input;
        private final OutputStream output;
        private final SocketAddress remoteAddress;
        private boolean outputShutdown;
        private boolean closed;

        private StubSocket(InputStream input, OutputStream output, SocketAddress remoteAddress) {
            this.input = input;
            this.output = output;
            this.remoteAddress = remoteAddress;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return remoteAddress;
        }

        @Override
        public void shutdownOutput() {
            outputShutdown = true;
        }

        @Override
        public boolean isOutputShutdown() {
            return outputShutdown;
        }

        @Override
        public synchronized void close() {
            closed = true;
        }

        private boolean isClosedByTest() {
            return closed;
        }
    }
}
