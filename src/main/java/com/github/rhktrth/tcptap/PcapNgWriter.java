/*
 * Copyright (C) 2011-2026 rhktrth
 * This software is under the terms of MIT license.
 */

package com.github.rhktrth.tcptap;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;

import com.github.rhktrth.tcptap.TrafficObserver.Direction;

final class PcapNgWriter implements Closeable {
    private static final int SECTION_HEADER_BLOCK = 0x0A0D0D0A;
    private static final int INTERFACE_DESCRIPTION_BLOCK = 0x00000001;
    private static final int ENHANCED_PACKET_BLOCK = 0x00000006;
    private static final int BYTE_ORDER_MAGIC = 0x1A2B3C4D;
    private static final int LINKTYPE_RAW = 101;
    private static final int LINKTYPE_USER0 = 147;
    private static final int RECONSTRUCTED_INTERFACE_ID = 0;
    private static final int DIAGNOSTIC_INTERFACE_ID = 1;
    private static final int SNAPLEN = 65535;
    private static final int OPTION_COMMENT = 1;
    private static final int OPTION_INTERFACE_NAME = 2;
    private static final int MAX_DIAGNOSTIC_VALUE_CHARS = 4096;

    private static final int TCP_FIN = 0x01;
    private static final int TCP_SYN = 0x02;
    private static final int TCP_RST = 0x04;
    private static final int TCP_PSH = 0x08;
    private static final int TCP_ACK = 0x10;

    private static final String CAPTURE_COMMENT =
            "TcpTap capture: interface 0 contains reconstructed synthetic TCP/IP packets; "
                    + "TCP/IP headers, sequence numbers, packet boundaries, and timestamps are "
                    + "synthetic representation values, not wire-captured values. Interface 1 "
                    + "contains TcpTap diagnostic events, also not wire-captured packets.";
    private static final String RECONSTRUCTED_INTERFACE_NAME = "tcptap-reconstructed";
    private static final String DIAGNOSTIC_INTERFACE_NAME = "tcptap-diagnostics";

    private final OutputStream output;
    private boolean failed;

    PcapNgWriter(File file) throws IOException {
        output = new BufferedOutputStream(Files.newOutputStream(
                file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
        try {
            writeSectionHeader();
            writeInterfaceDescription(LINKTYPE_RAW, RECONSTRUCTED_INTERFACE_NAME);
            writeInterfaceDescription(LINKTYPE_USER0, DIAGNOSTIC_INTERFACE_NAME);
            output.flush();
        } catch (IOException e) {
            try {
                output.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    SessionCapture startSession(long sessionId, Socket clientSocket, Socket destinationSocket) {
        Endpoint client = endpoint(clientSocket.getRemoteSocketAddress());
        Endpoint destination = endpoint(destinationSocket.getRemoteSocketAddress());
        if (client == null || destination == null) {
            System.err.printf("#%06d CAPTURE_SKIP endpoint address is unavailable%n", sessionId);
            return null;
        }
        return new SessionCapture(sessionId, client, destination);
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
        writeDiagnostic(sessionId, payload, comment);
    }

    @Override
    public synchronized void close() throws IOException {
        output.close();
    }

    private static Endpoint endpoint(SocketAddress socketAddress) {
        if (!(socketAddress instanceof InetSocketAddress)) {
            return null;
        }
        InetSocketAddress inetSocketAddress = (InetSocketAddress) socketAddress;
        InetAddress address = inetSocketAddress.getAddress();
        if (address == null) {
            return null;
        }
        return new Endpoint(address.getAddress(), inetSocketAddress.getPort());
    }

    private void writePacket(long sessionId, byte[] packet, long timestampMicros) {
        writeEnhancedPacket(
                sessionId,
                RECONSTRUCTED_INTERFACE_ID,
                packet,
                timestampMicros,
                null);
    }

    private void writeDiagnostic(long sessionId, String payload, String comment) {
        writeEnhancedPacket(
                sessionId,
                DIAGNOSTIC_INTERFACE_ID,
                payload.getBytes(StandardCharsets.UTF_8),
                epochMicros(),
                comment);
    }

    private synchronized void writeEnhancedPacket(
            long sessionId,
            int interfaceId,
            byte[] packet,
            long timestampMicros,
            String comment) {
        if (failed) {
            return;
        }
        try {
            int paddedPacketLength = paddedLength(packet.length);
            byte[] commentBytes = comment == null
                    ? null
                    : comment.getBytes(StandardCharsets.UTF_8);
            int blockLength = 32 + paddedPacketLength;
            if (commentBytes != null) {
                blockLength += 8 + paddedLength(commentBytes.length);
            }

            writeIntLE(ENHANCED_PACKET_BLOCK);
            writeIntLE(blockLength);
            writeIntLE(interfaceId);
            writeIntLE((int) (timestampMicros >>> 32));
            writeIntLE((int) timestampMicros);
            writeIntLE(packet.length);
            writeIntLE(packet.length);
            output.write(packet);
            writePadding(packet.length, paddedPacketLength);
            if (commentBytes != null) {
                writeOption(OPTION_COMMENT, commentBytes);
                writeOptionEnd();
            }
            writeIntLE(blockLength);
            output.flush();
        } catch (IOException e) {
            failed = true;
            System.err.printf("#%06d CAPTURE_ERROR %s: %s%n",
                    sessionId,
                    e.getClass().getSimpleName(),
                    safeMessage(e));
        }
    }

    private void writeSectionHeader() throws IOException {
        byte[] comment = CAPTURE_COMMENT.getBytes(StandardCharsets.UTF_8);
        int commentPaddedLength = paddedLength(comment.length);
        int blockLength = 36 + commentPaddedLength;

        writeIntLE(SECTION_HEADER_BLOCK);
        writeIntLE(blockLength);
        writeIntLE(BYTE_ORDER_MAGIC);
        writeShortLE(1);
        writeShortLE(0);
        writeLongLE(-1L);
        writeOption(OPTION_COMMENT, comment);
        writeOptionEnd();
        writeIntLE(blockLength);
    }

    private void writeInterfaceDescription(int linkType, String name) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int blockLength = 28 + paddedLength(nameBytes.length);

        writeIntLE(INTERFACE_DESCRIPTION_BLOCK);
        writeIntLE(blockLength);
        writeShortLE(linkType);
        writeShortLE(0);
        writeIntLE(SNAPLEN);
        writeOption(OPTION_INTERFACE_NAME, nameBytes);
        writeOptionEnd();
        writeIntLE(blockLength);
    }

    private void writeOption(int optionCode, byte[] value) throws IOException {
        int paddedValueLength = paddedLength(value.length);
        writeShortLE(optionCode);
        writeShortLE(value.length);
        output.write(value);
        writePadding(value.length, paddedValueLength);
    }

    private void writeOptionEnd() throws IOException {
        writeShortLE(0);
        writeShortLE(0);
    }

    private static int paddedLength(int length) {
        return (length + 3) & ~3;
    }

    private void writePadding(int length, int paddedLength) throws IOException {
        for (int i = length; i < paddedLength; i++) {
            output.write(0);
        }
    }

    private void writeShortLE(int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private void writeIntLE(int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private void writeLongLE(long value) throws IOException {
        for (int i = 0; i < 8; i++) {
            output.write((int) (value >>> (i * 8)) & 0xff);
        }
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

    private static long epochMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }

    private static final class Endpoint {
        private final byte[] address;
        private final int port;

        private Endpoint(byte[] address, int port) {
            this.address = address;
            this.port = port;
        }
    }

    final class SessionCapture implements TrafficObserver {
        private static final long UINT32_MASK = 0xffffffffL;

        private final long sessionId;
        private final byte[] clientAddress;
        private final int clientPort;
        private final byte[] destinationAddress;
        private final int destinationPort;
        private final boolean ipv6;
        private long clientSequence;
        private long destinationSequence;
        private int packetId;
        private boolean clientFinished;
        private boolean destinationFinished;

        private SessionCapture(long sessionId, Endpoint client, Endpoint destination) {
            this.sessionId = sessionId;
            ipv6 = client.address.length != 4 || destination.address.length != 4;
            clientAddress = ipv6 ? toIpv6(client.address) : client.address.clone();
            destinationAddress = ipv6 ? toIpv6(destination.address) : destination.address.clone();
            clientPort = client.port;
            destinationPort = destination.port;
            packetId = (int) sessionId;

            long clientInitial = (0x13572468L ^ (sessionId * 0x9E3779B1L)) & UINT32_MASK;
            long destinationInitial = (0x24681357L ^ (sessionId * 0x7F4A7C15L)) & UINT32_MASK;

            emit(clientAddress, clientPort, destinationAddress, destinationPort,
                    clientInitial, 0, TCP_SYN, null, 0, 0, epochMicros());
            emit(destinationAddress, destinationPort, clientAddress, clientPort,
                    destinationInitial, add32(clientInitial, 1), TCP_SYN | TCP_ACK,
                    null, 0, 0, epochMicros());
            emit(clientAddress, clientPort, destinationAddress, destinationPort,
                    add32(clientInitial, 1), add32(destinationInitial, 1), TCP_ACK,
                    null, 0, 0, epochMicros());

            clientSequence = add32(clientInitial, 1);
            destinationSequence = add32(destinationInitial, 1);
        }

        @Override
        public void onData(Direction direction, byte[] data, int offset, int length) {
            switch (direction) {
                case CLIENT_TO_DESTINATION:
                    recordClientData(data, offset, length);
                    break;
                case DESTINATION_TO_CLIENT:
                    recordDestinationData(data, offset, length);
                    break;
                default:
                    throw new AssertionError(direction);
            }
        }

        @Override
        public void onEof(Direction direction) {
            switch (direction) {
                case CLIENT_TO_DESTINATION:
                    recordClientEof();
                    break;
                case DESTINATION_TO_CLIENT:
                    recordDestinationEof();
                    break;
                default:
                    throw new AssertionError(direction);
            }
        }

        @Override
        public void onError(Direction direction) {
            switch (direction) {
                case CLIENT_TO_DESTINATION:
                    recordClientError();
                    break;
                case DESTINATION_TO_CLIENT:
                    recordDestinationError();
                    break;
                default:
                    throw new AssertionError(direction);
            }
        }

        synchronized void recordClientData(byte[] data, int offset, int length) {
            if (length <= 0 || clientFinished) {
                return;
            }
            emit(clientAddress, clientPort, destinationAddress, destinationPort,
                    clientSequence, destinationSequence, TCP_PSH | TCP_ACK,
                    data, offset, length, epochMicros());
            clientSequence = add32(clientSequence, length);
        }

        synchronized void recordDestinationData(byte[] data, int offset, int length) {
            if (length <= 0 || destinationFinished) {
                return;
            }
            emit(destinationAddress, destinationPort, clientAddress, clientPort,
                    destinationSequence, clientSequence, TCP_PSH | TCP_ACK,
                    data, offset, length, epochMicros());
            destinationSequence = add32(destinationSequence, length);
        }

        synchronized void recordClientEof() {
            if (clientFinished) {
                return;
            }
            emit(clientAddress, clientPort, destinationAddress, destinationPort,
                    clientSequence, destinationSequence, TCP_FIN | TCP_ACK,
                    null, 0, 0, epochMicros());
            clientSequence = add32(clientSequence, 1);
            clientFinished = true;
        }

        synchronized void recordDestinationEof() {
            if (destinationFinished) {
                return;
            }
            emit(destinationAddress, destinationPort, clientAddress, clientPort,
                    destinationSequence, clientSequence, TCP_FIN | TCP_ACK,
                    null, 0, 0, epochMicros());
            destinationSequence = add32(destinationSequence, 1);
            destinationFinished = true;
        }

        synchronized void recordClientError() {
            if (clientFinished) {
                return;
            }
            emit(clientAddress, clientPort, destinationAddress, destinationPort,
                    clientSequence, destinationSequence, TCP_RST | TCP_ACK,
                    null, 0, 0, epochMicros());
            clientFinished = true;
        }

        synchronized void recordDestinationError() {
            if (destinationFinished) {
                return;
            }
            emit(destinationAddress, destinationPort, clientAddress, clientPort,
                    destinationSequence, clientSequence, TCP_RST | TCP_ACK,
                    null, 0, 0, epochMicros());
            destinationFinished = true;
        }

        private void emit(byte[] sourceAddress, int sourcePort, byte[] targetAddress, int targetPort,
                long sequence, long acknowledgment, int flags,
                byte[] payload, int payloadOffset, int payloadLength, long timestampMicros) {
            byte[] packet = ipv6
                    ? buildIpv6Packet(sourceAddress, sourcePort, targetAddress, targetPort,
                            sequence, acknowledgment, flags, payload, payloadOffset, payloadLength)
                    : buildIpv4Packet(sourceAddress, sourcePort, targetAddress, targetPort,
                            sequence, acknowledgment, flags, payload, payloadOffset, payloadLength,
                            packetId++);
            writePacket(sessionId, packet, timestampMicros);
        }

        private long add32(long value, long increment) {
            return (value + increment) & UINT32_MASK;
        }
    }

    private static byte[] toIpv6(byte[] address) {
        if (address.length == 16) {
            return address.clone();
        }
        if (address.length != 4) {
            throw new IllegalArgumentException("unsupported IP address length: " + address.length);
        }
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        System.arraycopy(address, 0, mapped, 12, 4);
        return mapped;
    }

    private static byte[] buildIpv4Packet(byte[] sourceAddress, int sourcePort,
            byte[] destinationAddress, int destinationPort,
            long sequence, long acknowledgment, int flags,
            byte[] payload, int payloadOffset, int payloadLength, int packetId) {
        int ipHeaderLength = 20;
        int tcpHeaderLength = 20;
        int tcpLength = tcpHeaderLength + payloadLength;
        byte[] packet = new byte[ipHeaderLength + tcpLength];

        packet[0] = 0x45;
        write16(packet, 2, packet.length);
        write16(packet, 4, packetId);
        write16(packet, 6, 0x4000);
        packet[8] = 64;
        packet[9] = 6;
        System.arraycopy(sourceAddress, 0, packet, 12, 4);
        System.arraycopy(destinationAddress, 0, packet, 16, 4);

        writeTcpHeader(packet, ipHeaderLength, sourcePort, destinationPort,
                sequence, acknowledgment, flags, payload, payloadOffset, payloadLength);
        write16(packet, 10, checksum(packet, 0, ipHeaderLength));
        write16(packet, ipHeaderLength + 16,
                tcpChecksumIpv4(packet, ipHeaderLength, tcpLength));
        return packet;
    }

    private static byte[] buildIpv6Packet(byte[] sourceAddress, int sourcePort,
            byte[] destinationAddress, int destinationPort,
            long sequence, long acknowledgment, int flags,
            byte[] payload, int payloadOffset, int payloadLength) {
        int ipHeaderLength = 40;
        int tcpHeaderLength = 20;
        int tcpLength = tcpHeaderLength + payloadLength;
        byte[] packet = new byte[ipHeaderLength + tcpLength];

        packet[0] = 0x60;
        write16(packet, 4, tcpLength);
        packet[6] = 6;
        packet[7] = 64;
        System.arraycopy(sourceAddress, 0, packet, 8, 16);
        System.arraycopy(destinationAddress, 0, packet, 24, 16);

        writeTcpHeader(packet, ipHeaderLength, sourcePort, destinationPort,
                sequence, acknowledgment, flags, payload, payloadOffset, payloadLength);
        write16(packet, ipHeaderLength + 16,
                tcpChecksumIpv6(packet, ipHeaderLength, tcpLength));
        return packet;
    }

    private static void writeTcpHeader(byte[] packet, int offset, int sourcePort, int destinationPort,
            long sequence, long acknowledgment, int flags,
            byte[] payload, int payloadOffset, int payloadLength) {
        write16(packet, offset, sourcePort);
        write16(packet, offset + 2, destinationPort);
        write32(packet, offset + 4, sequence);
        write32(packet, offset + 8, acknowledgment);
        packet[offset + 12] = 0x50;
        packet[offset + 13] = (byte) flags;
        write16(packet, offset + 14, 65535);
        if (payloadLength > 0) {
            System.arraycopy(payload, payloadOffset, packet, offset + 20, payloadLength);
        }
    }

    private static int tcpChecksumIpv4(byte[] packet, int tcpOffset, int tcpLength) {
        long sum = 0;
        sum = addWords(sum, packet, 12, 8);
        sum += 6;
        sum += tcpLength;
        sum = addWords(sum, packet, tcpOffset, tcpLength);
        return finishChecksum(sum);
    }

    private static int tcpChecksumIpv6(byte[] packet, int tcpOffset, int tcpLength) {
        long sum = 0;
        sum = addWords(sum, packet, 8, 32);
        sum += (tcpLength >>> 16) & 0xffff;
        sum += tcpLength & 0xffff;
        sum += 6;
        sum = addWords(sum, packet, tcpOffset, tcpLength);
        return finishChecksum(sum);
    }

    private static int checksum(byte[] data, int offset, int length) {
        return finishChecksum(addWords(0, data, offset, length));
    }

    private static long addWords(long sum, byte[] data, int offset, int length) {
        int end = offset + length;
        int i = offset;
        while (i + 1 < end) {
            sum += ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
            i += 2;
        }
        if (i < end) {
            sum += (data[i] & 0xff) << 8;
        }
        return sum;
    }

    private static int finishChecksum(long sum) {
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        return (int) (~sum) & 0xffff;
    }

    private static void write16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    private static void write32(byte[] data, int offset, long value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }
}
