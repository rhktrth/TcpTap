/*
 * Copyright (C) 2011-2026 rhktrth
 * This software is under the terms of MIT license.
 */

package com.github.rhktrth.tcptap;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import com.github.rhktrth.tcptap.TrafficObserver.Direction;

final class SyntheticTcpSession implements TrafficObserver {
    private static final long UINT32_MASK = 0xffffffffL;
    private static final int TCP_FIN = 0x01;
    private static final int TCP_SYN = 0x02;
    private static final int TCP_RST = 0x04;
    private static final int TCP_PSH = 0x08;
    private static final int TCP_ACK = 0x10;

    private final PcapNgEncoder encoder;
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

    static SyntheticTcpSession create(PcapNgEncoder encoder, long sessionId,
            SocketAddress clientSocketAddress, SocketAddress destinationSocketAddress) {
        Endpoint client = endpoint(clientSocketAddress);
        Endpoint destination = endpoint(destinationSocketAddress);
        if (client == null || destination == null) {
            return null;
        }
        return new SyntheticTcpSession(encoder, sessionId, client, destination);
    }

    private SyntheticTcpSession(PcapNgEncoder encoder, long sessionId, Endpoint client, Endpoint destination) {
        this.encoder = encoder;
        this.sessionId = sessionId;
        this.ipv6 = client.address.length != 4 || destination.address.length != 4;
        this.clientAddress = ipv6 ? toIpv6(client.address) : client.address.clone();
        this.destinationAddress = ipv6 ? toIpv6(destination.address) : destination.address.clone();
        this.clientPort = client.port;
        this.destinationPort = destination.port;
        this.packetId = (int) sessionId;

        long clientInitial = (0x13572468L ^ (sessionId * 0x9E3779B1L)) & UINT32_MASK;
        long destinationInitial = (0x24681357L ^ (sessionId * 0x7F4A7C15L)) & UINT32_MASK;

        emit(clientAddress, clientPort, destinationAddress, destinationPort,
                clientInitial, 0, TCP_SYN, null, 0, 0, PcapNgEncoder.epochMicros());
        emit(destinationAddress, destinationPort, clientAddress, clientPort,
                destinationInitial, add32(clientInitial, 1), TCP_SYN | TCP_ACK,
                null, 0, 0, PcapNgEncoder.epochMicros());
        emit(clientAddress, clientPort, destinationAddress, destinationPort,
                add32(clientInitial, 1), add32(destinationInitial, 1), TCP_ACK,
                null, 0, 0, PcapNgEncoder.epochMicros());

        this.clientSequence = add32(clientInitial, 1);
        this.destinationSequence = add32(destinationInitial, 1);
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
                data, offset, length, PcapNgEncoder.epochMicros());
        clientSequence = add32(clientSequence, length);
    }

    synchronized void recordDestinationData(byte[] data, int offset, int length) {
        if (length <= 0 || destinationFinished) {
            return;
        }
        emit(destinationAddress, destinationPort, clientAddress, clientPort,
                destinationSequence, clientSequence, TCP_PSH | TCP_ACK,
                data, offset, length, PcapNgEncoder.epochMicros());
        destinationSequence = add32(destinationSequence, length);
    }

    synchronized void recordClientEof() {
        if (clientFinished) {
            return;
        }
        emit(clientAddress, clientPort, destinationAddress, destinationPort,
                clientSequence, destinationSequence, TCP_FIN | TCP_ACK,
                null, 0, 0, PcapNgEncoder.epochMicros());
        clientSequence = add32(clientSequence, 1);
        clientFinished = true;
    }

    synchronized void recordDestinationEof() {
        if (destinationFinished) {
            return;
        }
        emit(destinationAddress, destinationPort, clientAddress, clientPort,
                destinationSequence, clientSequence, TCP_FIN | TCP_ACK,
                null, 0, 0, PcapNgEncoder.epochMicros());
        destinationSequence = add32(destinationSequence, 1);
        destinationFinished = true;
    }

    synchronized void recordClientError() {
        if (clientFinished) {
            return;
        }
        emit(clientAddress, clientPort, destinationAddress, destinationPort,
                clientSequence, destinationSequence, TCP_RST | TCP_ACK,
                null, 0, 0, PcapNgEncoder.epochMicros());
        clientFinished = true;
    }

    synchronized void recordDestinationError() {
        if (destinationFinished) {
            return;
        }
        emit(destinationAddress, destinationPort, clientAddress, clientPort,
                destinationSequence, clientSequence, TCP_RST | TCP_ACK,
                null, 0, 0, PcapNgEncoder.epochMicros());
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
        encoder.writeReconstructedPacket(sessionId, packet, timestampMicros);
    }

    private long add32(long value, long increment) {
        return (value + increment) & UINT32_MASK;
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

    private static final class Endpoint {
        private final byte[] address;
        private final int port;

        private Endpoint(byte[] address, int port) {
            this.address = address;
            this.port = port;
        }
    }
}
