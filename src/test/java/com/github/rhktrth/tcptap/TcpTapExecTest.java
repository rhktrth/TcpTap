package com.github.rhktrth.tcptap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class TcpTapExecTest {
    private static final int ENHANCED_PACKET_BLOCK = 0x00000006;

    @Test
    void helpUsesGeneratedCurrentOptions() throws Exception {
        Execution execution = execute("--help");

        assertEquals(0, execution.exitCode);
        assertTrue(execution.out.contains("Usage: TcpTap"));
        assertTrue(execution.out.contains("--listen-host"));
        assertTrue(execution.out.contains("--connect-timeout"));
        assertTrue(execution.out.contains("--capture"));
    }

    @Test
    void usageErrorsReturnExitCodeTwoWithoutStackTrace() throws Exception {
        String[][] invalidArguments = {
            {"--listen-port", "0"},
            {"--connect-timeout", "0"},
            {"--unknown"},
            {"--dest-port"}
        };

        for (String[] arguments : invalidArguments) {
            Execution execution = execute(arguments);
            assertEquals(2, execution.exitCode);
            assertFalse(execution.err.contains("Exception in thread"));
            assertFalse(execution.err.contains("\tat "));
        }
    }

    @Test
    void acceptsPortBoundariesDuringParsing() {
        new CommandLine(new TcpTapExec()).parseArgs(
                "--listen-port", "1",
                "--dest-port", "65535");
    }

    @Test
    void rejectsInvalidPorts() {
        String[] invalidPorts = {"abc", "0", "65536", "-1"};

        for (String port : invalidPorts) {
            assertThrows(
                    CommandLine.ParameterException.class,
                    () -> new CommandLine(new TcpTapExec()).parseArgs("--listen-port", port));
        }
    }

    @Test
    void rejectsInvalidConnectTimeout() {
        String[] invalidTimeouts = {"abc", "0", "-1", "2147483648"};

        for (String timeout : invalidTimeouts) {
            assertThrows(
                    CommandLine.ParameterException.class,
                    () -> new CommandLine(new TcpTapExec()).parseArgs("--connect-timeout", timeout));
        }
    }

    @Test
    void rejectsBlankValues() {
        assertThrows(
                CommandLine.ParameterException.class,
                () -> new CommandLine(new TcpTapExec()).parseArgs("--listen-host", ""));
        assertThrows(
                CommandLine.ParameterException.class,
                () -> new CommandLine(new TcpTapExec()).parseArgs("--dest-host", "   "));
        assertThrows(
                CommandLine.ParameterException.class,
                () -> new CommandLine(new TcpTapExec()).parseArgs("--capture", "   "));
    }

    @Test
    void rejectsDuplicateAndUnknownOptions() {
        assertThrows(
                CommandLine.ParameterException.class,
                () -> new CommandLine(new TcpTapExec()).parseArgs(
                        "--listen-port", "8080",
                        "--listen-port", "8081"));
        assertThrows(
                CommandLine.ParameterException.class,
                () -> new CommandLine(new TcpTapExec()).parseArgs("--unknown"));
    }

    @Test
    void rejectsRemovedLegacyOptionNames() {
        assertThrows(
                CommandLine.ParameterException.class,
                () -> new CommandLine(new TcpTapExec()).parseArgs("-listenport", "1234"));
    }

    @Test
    void listenerStartupFailureDoesNotCreateCaptureFile() throws Exception {
        Path directory = Files.createTempDirectory("tcptap-startup-");
        Path captureFile = directory.resolve("must-not-be-created.pcapng");

        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            Execution execution = execute(
                    "--listen-host", "127.0.0.1",
                    "--listen-port", Integer.toString(occupied.getLocalPort()),
                    "--capture", captureFile.toString());

            assertEquals(1, execution.exitCode);
            assertTrue(execution.err.contains("STARTUP_OR_LISTENER_ERROR"));
            assertFalse(Files.exists(captureFile));
        } finally {
            Files.deleteIfExists(captureFile);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void existingCaptureFileIsNotOverwrittenAtStartup() throws Exception {
        Path captureFile = Files.createTempFile("tcptap-existing-", ".pcapng");
        byte[] original = "keep-this-file".getBytes(StandardCharsets.UTF_8);
        Files.write(captureFile, original);

        try {
            Execution execution = execute(
                    "--listen-host", "127.0.0.1",
                    "--listen-port", Integer.toString(freePort()),
                    "--capture", captureFile.toString());

            assertEquals(1, execution.exitCode);
            assertArrayEquals(original, Files.readAllBytes(captureFile));
        } finally {
            Files.deleteIfExists(captureFile);
        }
    }

    @Test
    void destinationConnectFailureIsWrittenToDiagnosticCapture() throws Exception {
        Path directory = Files.createTempDirectory("tcptap-connect-error-");
        Path captureFile = directory.resolve("session.pcapng");
        int destinationPort = freePort();
        String destination = "127.0.0.1:" + destinationPort;

        TcpTapExec application = new TcpTapExec();
        new CommandLine(application).parseArgs(
                "--dest-host", "127.0.0.1",
                "--dest-port", Integer.toString(destinationPort),
                "--connect-timeout", "1000");

        try (ServerSocket clientSideListener = new ServerSocket(0);
                Socket client = new Socket("127.0.0.1", clientSideListener.getLocalPort());
                Socket relayClient = clientSideListener.accept();
                PcapNgWriter writer = new PcapNgWriter(captureFile.toFile())) {
            application.handleSession(91, System.nanoTime(), destination, relayClient, writer);
        }

        byte[] file = Files.readAllBytes(captureFile);
        assertTrue(hasDiagnosticConnectError(file, 91, destination));

        Files.deleteIfExists(captureFile);
        Files.deleteIfExists(directory);
    }

    @Test
    void acceptsNextClientWhilePreviousDestinationConnectIsPending() throws Exception {
        CountDownLatch firstConnectStarted = new CountDownLatch(1);
        CountDownLatch secondConnectStarted = new CountDownLatch(1);
        CountDownLatch releaseConnects = new CountDownLatch(1);
        AtomicInteger nextSocket = new AtomicInteger();
        Socket[] destinationSockets = {
            new BlockingConnectSocket(firstConnectStarted, releaseConnects),
            new BlockingConnectSocket(secondConnectStarted, releaseConnects)
        };
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        TcpTapExec application = configuredApplication(
                outputBytes,
                errorBytes,
                () -> destinationSockets[nextSocket.getAndIncrement()]);
        AtomicReference<Throwable> acceptFailure = new AtomicReference<Throwable>();

        try (ServerSocket listener = new ServerSocket(0)) {
            Thread acceptThread = startAcceptLoop(
                    application, listener, "127.0.0.1:9", acceptFailure);
            try {
                try (Socket firstClient = new Socket("127.0.0.1", listener.getLocalPort())) {
                    assertTrue(firstConnectStarted.await(2, TimeUnit.SECONDS));

                    try (Socket secondClient = new Socket("127.0.0.1", listener.getLocalPort())) {
                        assertTrue(secondConnectStarted.await(2, TimeUnit.SECONDS));
                    }
                }
            } finally {
                releaseConnects.countDown();
                stopAcceptLoop(listener, acceptThread, acceptFailure);
            }
        }

        assertTrue(waitForOccurrences(
                outputBytes, "CONNECT_ERROR destination=127.0.0.1:9", 2));
    }

    @Test
    void continuesAcceptingAfterDestinationConnectFailure() throws Exception {
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        TcpTapExec application = configuredApplication(
                outputBytes,
                errorBytes,
                FailingConnectSocket::new);
        AtomicReference<Throwable> acceptFailure = new AtomicReference<Throwable>();

        try (ServerSocket listener = new ServerSocket(0)) {
            Thread acceptThread = startAcceptLoop(
                    application, listener, "127.0.0.1:9", acceptFailure);
            try {
                try (Socket firstClient = new Socket("127.0.0.1", listener.getLocalPort())) {
                    assertTrue(waitForOccurrences(
                            outputBytes, "CONNECT_ERROR destination=127.0.0.1:9", 1));
                }

                try (Socket secondClient = new Socket("127.0.0.1", listener.getLocalPort())) {
                    assertTrue(waitForOccurrences(
                            outputBytes, "CONNECT_ERROR destination=127.0.0.1:9", 2));
                }
            } finally {
                stopAcceptLoop(listener, acceptThread, acceptFailure);
            }
        }
    }

    @Test
    void diagnosticValuesKeep4096AndTruncateBeyondLimit() throws Exception {
        Path directory = Files.createTempDirectory("tcptap-diagnostic-limit-");
        Path captureFile = directory.resolve("session.pcapng");
        String destination = repeat('d', 4096);
        String message = repeat('m', 4097);

        try (PcapNgWriter writer = new PcapNgWriter(captureFile.toFile())) {
            writer.recordConnectError(92, destination, new IOException(message));
        }

        String payload = firstDiagnosticPayload(Files.readAllBytes(captureFile));
        assertNotNull(payload);
        assertTrue(payload.contains("\"destination\":\"" + destination + "\""));
        assertTrue(payload.contains(
                "\"message\":\"" + repeat('m', 4093) + "...\""));
        assertFalse(payload.contains("\"message\":\"" + message + "\""));

        Files.deleteIfExists(captureFile);
        Files.deleteIfExists(directory);
    }

    private static TcpTapExec configuredApplication(
            ByteArrayOutputStream outputBytes,
            ByteArrayOutputStream errorBytes,
            TcpTapExec.DestinationSocketFactory destinationSocketFactory) throws Exception {
        TcpTapExec application = new TcpTapExec(
                new PrintStream(outputBytes, true, StandardCharsets.UTF_8.name()),
                new PrintStream(errorBytes, true, StandardCharsets.UTF_8.name()),
                destinationSocketFactory);
        new CommandLine(application).parseArgs(
                "--dest-host", "127.0.0.1",
                "--dest-port", "9",
                "--connect-timeout", "5000");
        return application;
    }

    private static Thread startAcceptLoop(
            TcpTapExec application,
            ServerSocket listener,
            String destination,
            AtomicReference<Throwable> acceptFailure) {
        Thread acceptThread = new Thread(() -> {
            try {
                application.acceptConnections(listener, destination, null);
            } catch (IOException e) {
                if (!listener.isClosed()) {
                    acceptFailure.set(e);
                }
            } catch (Throwable throwable) {
                acceptFailure.set(throwable);
            }
        }, "TcpTapExecTest-accept-loop");
        acceptThread.start();
        return acceptThread;
    }

    private static void stopAcceptLoop(
            ServerSocket listener,
            Thread acceptThread,
            AtomicReference<Throwable> acceptFailure) throws Exception {
        listener.close();
        acceptThread.join(2000);
        assertFalse(acceptThread.isAlive());
        if (acceptFailure.get() != null) {
            throw new AssertionError("accept loop failed", acceptFailure.get());
        }
    }

    private static boolean waitForOccurrences(
            ByteArrayOutputStream outputBytes, String value, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            String output = outputBytes.toString(StandardCharsets.UTF_8.name());
            if (countOccurrences(output, value) >= expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return countOccurrences(
                outputBytes.toString(StandardCharsets.UTF_8.name()), value) >= expected;
    }

    private static int countOccurrences(String value, String expected) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }

    private static String repeat(char value, int count) {
        StringBuilder repeated = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            repeated.append(value);
        }
        return repeated.toString();
    }

    private static boolean hasDiagnosticConnectError(byte[] file, long sessionId, String destination) {
        String payload = firstDiagnosticPayload(file);
        return payload != null
                && payload.contains("\"event\":\"CONNECT_ERROR\"")
                && payload.contains("\"session\":" + sessionId)
                && payload.contains("\"destination\":\"" + destination + "\"");
    }

    private static String firstDiagnosticPayload(byte[] file) {
        int offset = 0;
        while (offset < file.length) {
            int blockType = readIntLE(file, offset);
            int blockLength = readIntLE(file, offset + 4);
            if (blockType == ENHANCED_PACKET_BLOCK && readIntLE(file, offset + 8) == 1) {
                int capturedLength = readIntLE(file, offset + 20);
                return new String(file, offset + 28, capturedLength, StandardCharsets.UTF_8);
            }
            offset += blockLength;
        }
        return null;
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Execution execute(String... args) throws Exception {
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        int exitCode = TcpTapExec.execute(
                args,
                new PrintStream(outputBytes, true, StandardCharsets.UTF_8.name()),
                new PrintStream(errorBytes, true, StandardCharsets.UTF_8.name()));
        return new Execution(
                exitCode,
                outputBytes.toString(StandardCharsets.UTF_8.name()),
                errorBytes.toString(StandardCharsets.UTF_8.name()));
    }

    private static final class BlockingConnectSocket extends Socket {
        private final CountDownLatch connectStarted;
        private final CountDownLatch releaseConnect;

        private BlockingConnectSocket(
                CountDownLatch connectStarted, CountDownLatch releaseConnect) {
            this.connectStarted = connectStarted;
            this.releaseConnect = releaseConnect;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            connectStarted.countDown();
            try {
                releaseConnect.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while blocking connect", e);
            }
            throw new IOException("forced connect failure after blocking");
        }
    }

    private static final class FailingConnectSocket extends Socket {
        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            throw new IOException("forced connect failure");
        }
    }

    private static final class Execution {
        private final int exitCode;
        private final String out;
        private final String err;

        private Execution(int exitCode, String out, String err) {
            this.exitCode = exitCode;
            this.out = out;
            this.err = err;
        }
    }
}
