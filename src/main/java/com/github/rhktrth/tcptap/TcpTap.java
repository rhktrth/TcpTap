/*
 * Copyright (C) 2011-2026 rhktrth
 * This software is under the terms of MIT license.
 */

package com.github.rhktrth.tcptap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.util.Locale;

import com.github.rhktrth.tcptap.TrafficObserver.Direction;

final class TcpTap implements Runnable {
    private static final int BUFFER_SIZE = 16 * 1024;

    private final long sessionId;
    private final Socket clientSocket;
    private final Socket destinationSocket;
    private final long startedNanos;
    private final PrintStream out;
    private final StreamTap clientToDestination;
    private final StreamTap destinationToClient;

    TcpTap(long sessionId, Socket clientSocket, Socket destinationSocket, long startedNanos,
            PrintStream out, TrafficObserver observer) {
        this.sessionId = sessionId;
        this.clientSocket = clientSocket;
        this.destinationSocket = destinationSocket;
        this.startedNanos = startedNanos;
        this.out = out;
        TrafficObserver effectiveObserver = observer == null ? NoopTrafficObserver.INSTANCE : observer;
        clientToDestination = new StreamTap(
                Direction.CLIENT_TO_DESTINATION, clientSocket, destinationSocket, effectiveObserver);
        destinationToClient = new StreamTap(
                Direction.DESTINATION_TO_CLIENT, destinationSocket, clientSocket, effectiveObserver);
    }

    @Override
    public void run() {
        Thread clientToDestinationThread = new Thread(
                clientToDestination, "TcpTap-" + sessionId + "-C-to-D");
        Thread destinationToClientThread = new Thread(
                destinationToClient, "TcpTap-" + sessionId + "-D-to-C");
        clientToDestinationThread.start();
        destinationToClientThread.start();

        boolean interrupted = false;
        try {
            while (clientToDestinationThread.isAlive() || destinationToClientThread.isAlive()) {
                try {
                    clientToDestinationThread.join();
                    destinationToClientThread.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                    closeQuietly(clientSocket);
                    closeQuietly(destinationSocket);
                }
            }
        } finally {
            closeQuietly(clientSocket);
            closeQuietly(destinationSocket);
        }

        double durationSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        out.printf(Locale.ROOT,
                "#%06d CLOSE duration=%.3fs c2d=%dB d2c=%dB c2d_end=%s d2c_end=%s%n",
                sessionId,
                durationSeconds,
                clientToDestination.getBytesTransferred(),
                destinationToClient.getBytesTransferred(),
                clientToDestination.getEndReason(),
                destinationToClient.getEndReason());

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Socket cleanup is best-effort.
        }
    }

    private final class StreamTap implements Runnable {
        private final Direction direction;
        private final Socket inputSocket;
        private final Socket outputSocket;
        private final TrafficObserver observer;
        private long bytesTransferred;
        private String endReason = "UNKNOWN";

        private StreamTap(Direction direction, Socket inputSocket, Socket outputSocket,
                TrafficObserver observer) {
            this.direction = direction;
            this.inputSocket = inputSocket;
            this.outputSocket = outputSocket;
            this.observer = observer;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[BUFFER_SIZE];
            try {
                InputStream inputStream = inputSocket.getInputStream();
                OutputStream outputStream = outputSocket.getOutputStream();
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) >= 0) {
                    if (bytesRead == 0) {
                        continue;
                    }
                    observer.onData(direction, buffer, 0, bytesRead);
                    outputStream.write(buffer, 0, bytesRead);
                    bytesTransferred += bytesRead;
                }
                outputStream.flush();
                observer.onEof(direction);
                shutdownOutputQuietly(outputSocket);
                endReason = "EOF";
            } catch (IOException e) {
                observer.onError(direction);
                endReason = "IO_ERROR:" + e.getClass().getSimpleName();
                out.printf(Locale.ROOT,
                        "#%06d %s ERROR %s: %s%n",
                        sessionId,
                        direction.label(),
                        e.getClass().getSimpleName(),
                        safeMessage(e));
                closeQuietly(inputSocket);
                closeQuietly(outputSocket);
            }
        }

        private long getBytesTransferred() {
            return bytesTransferred;
        }

        private String getEndReason() {
            return endReason;
        }

        private void shutdownOutputQuietly(Socket socket) {
            try {
                if (!socket.isOutputShutdown()) {
                    socket.shutdownOutput();
                }
            } catch (IOException ignored) {
                // The peer may already have closed the connection.
            }
        }

        private String safeMessage(IOException e) {
            String message = e.getMessage();
            return message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
        }
    }
}
