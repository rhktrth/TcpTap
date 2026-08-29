/*
 * Copyright (C) 2011-2026 rhktrth
 * This software is under the terms of MIT license.
 */

package com.github.rhktrth.tcptap;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.TypeConversionException;

@Command(
        name = "TcpTap",
        description = "Relay a TCP byte stream to a fixed destination and observe each session.",
        sortOptions = false)
public final class TcpTapExec implements Callable<Integer> {
    private static final int EXIT_RUNTIME_ERROR = 1;
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();

    @Option(
            names = "--listen-host",
            defaultValue = "127.0.0.1",
            paramLabel = "HOST",
            converter = NonBlankStringConverter.class,
            description = "Local address to listen on (default: ${DEFAULT-VALUE}).")
    private String listenHost;

    @Option(
            names = "--listen-port",
            defaultValue = "8080",
            paramLabel = "PORT",
            converter = PortConverter.class,
            description = "TCP port to listen on (default: ${DEFAULT-VALUE}).")
    private int listenPort;

    @Option(
            names = "--dest-host",
            defaultValue = "localhost",
            paramLabel = "HOST",
            converter = NonBlankStringConverter.class,
            description = "Destination hostname (default: ${DEFAULT-VALUE}).")
    private String destinationHost;

    @Option(
            names = "--dest-port",
            defaultValue = "80",
            paramLabel = "PORT",
            converter = PortConverter.class,
            description = "Destination TCP port (default: ${DEFAULT-VALUE}).")
    private int destinationPort;

    @Option(
            names = "--connect-timeout",
            defaultValue = "10000",
            paramLabel = "MILLIS",
            converter = PositiveIntConverter.class,
            description = "Destination connect timeout in milliseconds (default: ${DEFAULT-VALUE}).")
    private int connectTimeoutMillis;

    @Option(
            names = "--capture",
            paramLabel = "FILE",
            converter = NonBlankPathConverter.class,
            description = "Write the stream observed by TcpTap and diagnostics as pcapng; FILE must not exist.")
    private Path captureFile;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help and exit.")
    private boolean helpRequested;

    private final PrintStream standardOut;
    private final PrintStream standardErr;
    private final DestinationSocketFactory destinationSocketFactory;

    public TcpTapExec() {
        this(System.out, System.err);
    }

    private TcpTapExec(PrintStream standardOut, PrintStream standardErr) {
        this(standardOut, standardErr, Socket::new);
    }

    TcpTapExec(PrintStream standardOut, PrintStream standardErr,
            DestinationSocketFactory destinationSocketFactory) {
        this.standardOut = standardOut;
        this.standardErr = standardErr;
        this.destinationSocketFactory = destinationSocketFactory;
    }

    public static void main(String[] args) {
        int exitCode = execute(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int execute(String[] args, PrintStream standardOut, PrintStream standardErr) {
        TcpTapExec application = new TcpTapExec(standardOut, standardErr);
        CommandLine commandLine = new CommandLine(application);
        commandLine.setOut(new PrintWriter(standardOut, true));
        commandLine.setErr(new PrintWriter(standardErr, true));
        commandLine.setExecutionExceptionHandler((exception, cmd, parseResult) -> {
            standardErr.printf("TcpTap: %s: %s%n",
                    exception.getClass().getSimpleName(), safeMessage(exception));
            return EXIT_RUNTIME_ERROR;
        });
        return commandLine.execute(args);
    }

    @Override
    public Integer call() {
        return runRelay();
    }

    private int runRelay() {
        final String configuredDestination = formatHostPort(destinationHost, destinationPort);
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(InetAddress.getByName(listenHost), listenPort));

            try (PcapNgWriter captureWriter = captureFile == null
                    ? null
                    : new PcapNgWriter(captureFile.toFile())) {
                if (captureFile != null) {
                    standardOut.printf("%s CAPTURE file=%s mode=reconstructed-stream%n",
                            Instant.now(), sanitize(captureFile.toString()));
                }

                standardOut.printf("%s LISTEN %s -> %s connect_timeout=%dms%n",
                        Instant.now(),
                        formatEndpoint(listener.getLocalSocketAddress()),
                        configuredDestination,
                        connectTimeoutMillis);

                acceptConnections(listener, configuredDestination, captureWriter);
                return 0;
            }
        } catch (IOException e) {
            standardErr.printf("%s STARTUP_OR_LISTENER_ERROR %s: %s%n",
                    Instant.now(),
                    e.getClass().getSimpleName(),
                    safeMessage(e));
            return EXIT_RUNTIME_ERROR;
        }
    }

    void acceptConnections(ServerSocket listener, String configuredDestination,
            PcapNgWriter captureWriter) throws IOException {
        while (true) {
            final Socket clientSocket = listener.accept();
            final long sessionId = NEXT_SESSION_ID.incrementAndGet();
            final long startedNanos = System.nanoTime();
            standardOut.printf(Locale.ROOT,
                    "%s #%06d ACCEPT client=%s%n",
                    Instant.now(),
                    sessionId,
                    formatEndpoint(clientSocket.getRemoteSocketAddress()));

            Thread sessionThread = new Thread(
                    () -> handleSession(
                            sessionId,
                            startedNanos,
                            configuredDestination,
                            clientSocket,
                            captureWriter),
                    "TcpTap-session-" + sessionId);
            sessionThread.start();
        }
    }

    void handleSession(long sessionId, long startedNanos, String configuredDestination,
            Socket clientSocket, PcapNgWriter captureWriter) {
        Socket destinationSocket = destinationSocketFactory.create();
        long connectStartedNanos = System.nanoTime();
        try {
            destinationSocket.connect(
                    new InetSocketAddress(destinationHost, destinationPort),
                    connectTimeoutMillis);
            double connectMillis = (System.nanoTime() - connectStartedNanos) / 1_000_000.0;
            standardOut.printf(Locale.ROOT,
                    "%s #%06d CONNECT destination=%s %.3fms%n",
                    Instant.now(),
                    sessionId,
                    formatEndpoint(destinationSocket.getRemoteSocketAddress()),
                    connectMillis);
            TrafficObserver observer = NoopTrafficObserver.INSTANCE;
            if (captureWriter != null) {
                PcapNgWriter.SessionCapture sessionCapture = captureWriter.startSession(
                        sessionId, clientSocket, destinationSocket);
                if (sessionCapture != null) {
                    observer = sessionCapture;
                }
            }
            new TcpTap(
                    sessionId,
                    clientSocket,
                    destinationSocket,
                    startedNanos,
                    standardOut,
                    observer).run();
        } catch (IOException e) {
            double connectMillis = (System.nanoTime() - connectStartedNanos) / 1_000_000.0;
            if (captureWriter != null) {
                captureWriter.recordConnectError(sessionId, configuredDestination, e);
            }
            standardOut.printf(Locale.ROOT,
                    "%s #%06d CONNECT_ERROR destination=%s %.3fms %s: %s%n",
                    Instant.now(),
                    sessionId,
                    configuredDestination,
                    connectMillis,
                    e.getClass().getSimpleName(),
                    safeMessage(e));
            closeQuietly(clientSocket);
            closeQuietly(destinationSocket);
        }
    }

    private static String formatEndpoint(SocketAddress socketAddress) {
        if (!(socketAddress instanceof InetSocketAddress)) {
            return String.valueOf(socketAddress);
        }
        InetSocketAddress address = (InetSocketAddress) socketAddress;
        String host = address.getAddress() == null
                ? address.getHostString()
                : address.getAddress().getHostAddress();
        return formatHostPort(host, address.getPort());
    }

    private static String formatHostPort(String host, int port) {
        String safeHost = sanitize(host);
        if (safeHost.indexOf(':') >= 0 && !(safeHost.startsWith("[") && safeHost.endsWith("]"))) {
            return "[" + safeHost + "]:" + port;
        }
        return safeHost + ":" + port;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Socket cleanup is best-effort.
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? "" : sanitize(message);
    }

    private static String sanitize(String value) {
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    interface DestinationSocketFactory {
        Socket create();
    }

    static final class NonBlankStringConverter implements CommandLine.ITypeConverter<String> {
        @Override
        public String convert(String value) {
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                throw new TypeConversionException("value must not be blank");
            }
            return trimmed;
        }
    }

    static final class PortConverter implements CommandLine.ITypeConverter<Integer> {
        @Override
        public Integer convert(String value) {
            int port = parseInteger(value);
            if (port < 1 || port > 65535) {
                throw new TypeConversionException("port must be between 1 and 65535: " + sanitize(value));
            }
            return port;
        }
    }

    static final class PositiveIntConverter implements CommandLine.ITypeConverter<Integer> {
        @Override
        public Integer convert(String value) {
            int parsed = parseInteger(value);
            if (parsed < 1) {
                throw new TypeConversionException("value must be at least 1: " + sanitize(value));
            }
            return parsed;
        }
    }

    static final class NonBlankPathConverter implements CommandLine.ITypeConverter<Path> {
        @Override
        public Path convert(String value) {
            if (value.trim().isEmpty()) {
                throw new TypeConversionException("path must not be blank");
            }
            return Paths.get(value);
        }
    }

    private static int parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new TypeConversionException("value must be an integer: " + sanitize(value));
        }
    }
}
