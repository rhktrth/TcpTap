/*
 * Copyright (C) 2011-2026 rhktrth
 * This software is under the terms of MIT license.
 */

package com.github.rhktrth.tcptap;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.Locale;

import com.github.rhktrth.tcptap.TrafficObserver.Direction;

final class PcapNgWriter implements Closeable {
    private static final int MAX_DIAGNOSTIC_VALUE_CHARS = 4096;

    private final PcapNgEncoder encoder;

    PcapNgWriter(File file) throws IOException {
        this.encoder = new PcapNgEncoder(file);
    }

    SessionCapture startSession(long sessionId, Socket clientSocket, Socket destinationSocket) {
        SyntheticTcpSession session = SyntheticTcpSession.create(
                encoder,
                sessionId,
                clientSocket.getRemoteSocketAddress(),
                destinationSocket.getRemoteSocketAddress());
        if (session == null) {
            System.err.printf("#%06d CAPTURE_SKIP endpoint address is unavailable%n", sessionId);
            return null;
        }
        return new SessionCapture(session);
    }

    void recordConnectError(long sessionId, String destination, Throwable throwable) {
        String safeDestination = diagnosticValue(destination);
        String exceptionName = throwable == null ? "" : throwable.getClass().getSimpleName();
        String message = diagnosticValue(safeMessage(throwable));
        String payload = "{"
                + "\"schema\":\"tcptap-diagnostic-v1\","
                + "\"event\":\"CONNECT_ERROR\","
                + "\"session\":" + sessionId + ","
                + "\"destination\":\"" + escapeJson(safeDestination) + "\","
                + "\"exception\":\"" + escapeJson(exceptionName) + "\","
                + "\"message\":\"" + escapeJson(message) + "\""
                + "}";
        String comment = String.format(
                Locale.ROOT,
                "TcpTap CONNECT_ERROR session=%d destination=%s %s: %s",
                sessionId,
                safeDestination,
                exceptionName,
                message);
        encoder.writeDiagnostic(sessionId, payload, comment);
    }

    @Override
    public void close() throws IOException {
        encoder.close();
    }

    private static String diagnosticValue(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replace('\n', ' ').replace('\r', ' ');
        if (sanitized.length() <= MAX_DIAGNOSTIC_VALUE_CHARS) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_DIAGNOSTIC_VALUE_CHARS - 3) + "...";
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        String message = throwable.getMessage();
        return message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int padding = hex.length(); padding < 4; padding++) {
                            escaped.append('0');
                        }
                        escaped.append(hex);
                    } else {
                        escaped.append(c);
                    }
                    break;
            }
        }
        return escaped.toString();
    }

    static final class SessionCapture implements TrafficObserver {
        private final SyntheticTcpSession session;

        private SessionCapture(SyntheticTcpSession session) {
            this.session = session;
        }

        @Override
        public void onData(Direction direction, byte[] data, int offset, int length) {
            session.onData(direction, data, offset, length);
        }

        @Override
        public void onEof(Direction direction) {
            session.onEof(direction);
        }

        @Override
        public void onError(Direction direction) {
            session.onError(direction);
        }

        void recordClientData(byte[] data, int offset, int length) {
            session.recordClientData(data, offset, length);
        }

        void recordDestinationData(byte[] data, int offset, int length) {
            session.recordDestinationData(data, offset, length);
        }

        void recordClientEof() {
            session.recordClientEof();
        }

        void recordDestinationEof() {
            session.recordDestinationEof();
        }

        void recordClientError() {
            session.recordClientError();
        }

        void recordDestinationError() {
            session.recordDestinationError();
        }
    }
}
