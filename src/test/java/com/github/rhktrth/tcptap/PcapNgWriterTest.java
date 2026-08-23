package com.github.rhktrth.tcptap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PcapNgWriterTest {
    private static final int SECTION_HEADER_BLOCK = 0x0A0D0D0A;
    private static final int INTERFACE_DESCRIPTION_BLOCK = 0x00000001;
    private static final int ENHANCED_PACKET_BLOCK = 0x00000006;
    private static final int LINKTYPE_RAW = 101;
    private static final int LINKTYPE_USER0 = 147;

    @Test
    void writesValidIpv4TcpConversationAndBothPayloadDirections() throws Exception {
        Path directory = Files.createTempDirectory("tcptap-");
        Path captureFile = directory.resolve("session.pcapng");
        byte[] request = "hello-from-client".getBytes(StandardCharsets.UTF_8);
        byte[] response = "hello-from-server".getBytes(StandardCharsets.UTF_8);
        int clientPort;
        int destinationPort;

        try (ServerSocket clientSideListener = new ServerSocket(0);
                ServerSocket destinationListener = new ServerSocket(0);
                Socket client = new Socket("127.0.0.1", clientSideListener.getLocalPort());
                Socket relayClient = clientSideListener.accept();
                Socket relayDestination = new Socket("127.0.0.1", destinationListener.getLocalPort());
                Socket destination = destinationListener.accept();
                PcapNgWriter writer = new PcapNgWriter(captureFile.toFile())) {

            clientPort = client.getLocalPort();
            destinationPort = destinationListener.getLocalPort();

            PcapNgWriter.SessionCapture capture = writer.startSession(42, relayClient, relayDestination);
            assertNotNull(capture);
            capture.recordClientData(request, 0, request.length);
            capture.recordDestinationData(response, 0, response.length);
            capture.recordClientEof();
            capture.recordDestinationEof();
        }

        byte[] file = Files.readAllBytes(captureFile);
        List<byte[]> packets = new ArrayList<byte[]>();
        int offset = 0;
        int blockIndex = 0;
        int interfaceCount = 0;
        boolean syntheticCommentFound = false;

        while (offset < file.length) {
            int blockType = readIntLE(file, offset);
            int blockLength = readIntLE(file, offset + 4);
            assertTrue(blockLength >= 12);
            assertTrue(offset + blockLength <= file.length);
            assertEquals(blockLength, readIntLE(file, offset + blockLength - 4));

            if (blockIndex == 0) {
                assertEquals(SECTION_HEADER_BLOCK, blockType);
                syntheticCommentFound = hasSyntheticSectionComment(file, offset, blockLength);
            }
            if (blockType == INTERFACE_DESCRIPTION_BLOCK) {
                interfaceCount++;
            }

            if (blockType == ENHANCED_PACKET_BLOCK) {
                int interfaceId = readIntLE(file, offset + 8);
                assertEquals(0, interfaceId);
                int capturedLength = readIntLE(file, offset + 20);
                int originalLength = readIntLE(file, offset + 24);
                assertEquals(capturedLength, originalLength);
                int packetOffset = offset + 28;
                byte[] packet = new byte[capturedLength];
                System.arraycopy(file, packetOffset, packet, 0, capturedLength);
                packets.add(packet);
            }

            offset += blockLength;
            blockIndex++;
        }

        assertEquals(file.length, offset);
        assertEquals(2, interfaceCount);
        assertEquals(7, packets.size());
        assertTrue(syntheticCommentFound);

        for (byte[] packet : packets) {
            assertValidIpv4TcpPacket(packet);
        }

        assertTcpPacket(packets.get(0), clientPort, destinationPort, 0x02, null);
        assertTcpPacket(packets.get(1), destinationPort, clientPort, 0x12, null);
        assertTcpPacket(packets.get(2), clientPort, destinationPort, 0x10, null);
        assertTcpPacket(packets.get(3), clientPort, destinationPort, 0x18, request);
        assertTcpPacket(packets.get(4), destinationPort, clientPort, 0x18, response);
        assertTcpPacket(packets.get(5), clientPort, destinationPort, 0x11, null);
        assertTcpPacket(packets.get(6), destinationPort, clientPort, 0x11, null);

        long clientInitial = tcpSequence(packets.get(0));
        long destinationInitial = tcpSequence(packets.get(1));
        long clientDataStart = add32(clientInitial, 1);
        long destinationDataStart = add32(destinationInitial, 1);
        long clientAfterData = add32(clientDataStart, request.length);
        long destinationAfterData = add32(destinationDataStart, response.length);

        assertEquals(0, tcpAcknowledgment(packets.get(0)));
        assertEquals(add32(clientInitial, 1), tcpAcknowledgment(packets.get(1)));
        assertEquals(clientDataStart, tcpSequence(packets.get(2)));
        assertEquals(destinationDataStart, tcpAcknowledgment(packets.get(2)));
        assertEquals(clientDataStart, tcpSequence(packets.get(3)));
        assertEquals(destinationDataStart, tcpAcknowledgment(packets.get(3)));
        assertEquals(destinationDataStart, tcpSequence(packets.get(4)));
        assertEquals(clientAfterData, tcpAcknowledgment(packets.get(4)));
        assertEquals(clientAfterData, tcpSequence(packets.get(5)));
        assertEquals(destinationAfterData, tcpAcknowledgment(packets.get(5)));
        assertEquals(destinationAfterData, tcpSequence(packets.get(6)));
        assertEquals(add32(clientAfterData, 1), tcpAcknowledgment(packets.get(6)));

        Files.deleteIfExists(captureFile);
        Files.deleteIfExists(directory);
    }

    @Test
    void writesIpv6ConversationAndMapsIpv4Endpoint() throws Exception {
        Path directory = Files.createTempDirectory("tcptap-ipv6-");
        Path captureFile = directory.resolve("session.pcapng");
        byte[] payload = "ipv6-payload".getBytes(StandardCharsets.UTF_8);
        int clientPort = 32100;
        int destinationPort = 443;

        Socket client = new AddressOnlySocket(new InetSocketAddress(
                InetAddress.getByName("::1"), clientPort));
        Socket destination = new AddressOnlySocket(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), destinationPort));

        try (PcapNgWriter writer = new PcapNgWriter(captureFile.toFile())) {
            PcapNgWriter.SessionCapture capture = writer.startSession(51, client, destination);
            assertNotNull(capture);
            capture.recordClientData(payload, 0, payload.length);
            capture.recordClientEof();
            capture.recordDestinationEof();
        }

        List<byte[]> packets = reconstructedPackets(Files.readAllBytes(captureFile));
        assertEquals(6, packets.size());
        for (byte[] packet : packets) {
            assertValidIpv6TcpPacket(packet);
        }

        byte[] first = packets.get(0);
        assertEquals(1, first[23] & 0xff);
        for (int i = 24; i < 34; i++) {
            assertEquals(0, first[i] & 0xff);
        }
        assertEquals(0xff, first[34] & 0xff);
        assertEquals(0xff, first[35] & 0xff);
        assertEquals(127, first[36] & 0xff);
        assertEquals(0, first[37] & 0xff);
        assertEquals(0, first[38] & 0xff);
        assertEquals(1, first[39] & 0xff);

        assertTcpPacket(first, clientPort, destinationPort, 0x02, null);
        assertTcpPacket(packets.get(3), clientPort, destinationPort, 0x18, payload);

        Files.deleteIfExists(captureFile);
        Files.deleteIfExists(directory);
    }

    @Test
    void suppressesEventsAfterDirectionHasFinished() throws Exception {
        Path directory = Files.createTempDirectory("tcptap-finished-");
        Path captureFile = directory.resolve("session.pcapng");
        Socket client = new AddressOnlySocket(new InetSocketAddress("127.0.0.1", 31001));
        Socket destination = new AddressOnlySocket(new InetSocketAddress("127.0.0.1", 31002));

        try (PcapNgWriter writer = new PcapNgWriter(captureFile.toFile())) {
            PcapNgWriter.SessionCapture capture = writer.startSession(61, client, destination);
            assertNotNull(capture);
            capture.recordClientEof();
            capture.recordClientEof();
            capture.recordClientError();
            capture.recordClientData(new byte[] {1}, 0, 1);
            capture.recordDestinationError();
            capture.recordDestinationError();
            capture.recordDestinationEof();
            capture.recordDestinationData(new byte[] {2}, 0, 1);
        }

        List<byte[]> packets = reconstructedPackets(Files.readAllBytes(captureFile));
        assertEquals(5, packets.size());
        assertEquals(0x11, tcpFlags(packets.get(3)));
        assertEquals(0x14, tcpFlags(packets.get(4)));

        Files.deleteIfExists(captureFile);
        Files.deleteIfExists(directory);
    }

    @Test
    void writesConnectErrorAsDiagnosticEventWithPacketComment() throws Exception {
        Path directory = Files.createTempDirectory("tcptap-diagnostic-");
        Path captureFile = directory.resolve("connect-error.pcapng");

        try (PcapNgWriter writer = new PcapNgWriter(captureFile.toFile())) {
            writer.recordConnectError(
                    7,
                    "10.8.1.1:443",
                    new ConnectException("Connection refused: no further information"));
        }

        byte[] file = Files.readAllBytes(captureFile);
        int offset = 0;
        int interfaceIndex = 0;
        EnhancedPacket diagnostic = null;

        while (offset < file.length) {
            int blockType = readIntLE(file, offset);
            int blockLength = readIntLE(file, offset + 4);
            assertTrue(blockLength >= 12);
            assertTrue(offset + blockLength <= file.length);
            assertEquals(blockLength, readIntLE(file, offset + blockLength - 4));

            if (blockType == INTERFACE_DESCRIPTION_BLOCK) {
                int linkType = readUnsignedShortLE(file, offset + 8);
                String name = findOptionString(file, offset + 16, offset + blockLength - 4, 2);
                if (interfaceIndex == 0) {
                    assertEquals(LINKTYPE_RAW, linkType);
                    assertEquals("tcptap-reconstructed", name);
                } else if (interfaceIndex == 1) {
                    assertEquals(LINKTYPE_USER0, linkType);
                    assertEquals("tcptap-diagnostics", name);
                }
                interfaceIndex++;
            } else if (blockType == ENHANCED_PACKET_BLOCK) {
                diagnostic = readEnhancedPacket(file, offset, blockLength);
            }

            offset += blockLength;
        }

        assertEquals(2, interfaceIndex);
        assertNotNull(diagnostic);
        assertEquals(1, diagnostic.interfaceId);
        String payload = new String(diagnostic.payload, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"schema\":\"tcptap-diagnostic-v1\""));
        assertTrue(payload.contains("\"event\":\"CONNECT_ERROR\""));
        assertTrue(payload.contains("\"session\":7"));
        assertTrue(payload.contains("\"destination\":\"10.8.1.1:443\""));
        assertTrue(payload.contains("\"exception\":\"ConnectException\""));
        assertTrue(payload.contains("Connection refused: no further information"));
        assertNotNull(diagnostic.comment);
        assertTrue(diagnostic.comment.contains("TcpTap CONNECT_ERROR"));
        assertTrue(diagnostic.comment.contains("session=7"));
        assertTrue(diagnostic.comment.contains("destination=10.8.1.1:443"));

        Files.deleteIfExists(captureFile);
        Files.deleteIfExists(directory);
    }

    @Test
    void sanitizesAndEscapesConnectErrorDiagnosticValues() throws Exception {
        Path directory = Files.createTempDirectory("tcptap-diagnostic-escape-");
        Path captureFile = directory.resolve("connect-error.pcapng");

        try (PcapNgWriter writer = new PcapNgWriter(captureFile.toFile())) {
            writer.recordConnectError(
                    8,
                    "dest\"\n\\host",
                    new IOException("line1\r\nline2\t\"\\"));
        }

        EnhancedPacket diagnostic = diagnosticPacket(Files.readAllBytes(captureFile));
        assertNotNull(diagnostic);
        String payload = new String(diagnostic.payload, StandardCharsets.UTF_8);
        assertFalse(payload.contains("\n"));
        assertFalse(payload.contains("\r"));
        assertTrue(payload.contains("\"destination\":\"dest\\\" \\\\host\""));
        assertTrue(payload.contains("line1  line2"));
        assertTrue(payload.contains("\\t"));
        assertTrue(payload.contains("\\\""));
        assertTrue(payload.contains("\\\\"));
        assertNotNull(diagnostic.comment);
        assertFalse(diagnostic.comment.contains("\n"));
        assertFalse(diagnostic.comment.contains("\r"));

        Files.deleteIfExists(captureFile);
        Files.deleteIfExists(directory);
    }

    @Test
    void doesNotOverwriteExistingCaptureFile() throws Exception {
        Path captureFile = Files.createTempFile("tcptap-existing-", ".pcapng");
        byte[] original = "keep-this-file".getBytes(StandardCharsets.UTF_8);
        Files.write(captureFile, original);

        assertThrows(IOException.class, () -> new PcapNgWriter(captureFile.toFile()));
        assertArrayEquals(original, Files.readAllBytes(captureFile));

        Files.deleteIfExists(captureFile);
    }

    private static List<byte[]> reconstructedPackets(byte[] file) {
        List<byte[]> packets = new ArrayList<byte[]>();
        int offset = 0;
        while (offset < file.length) {
            int blockType = readIntLE(file, offset);
            int blockLength = readIntLE(file, offset + 4);
            if (blockType == ENHANCED_PACKET_BLOCK && readIntLE(file, offset + 8) == 0) {
                int capturedLength = readIntLE(file, offset + 20);
                byte[] packet = new byte[capturedLength];
                System.arraycopy(file, offset + 28, packet, 0, capturedLength);
                packets.add(packet);
            }
            offset += blockLength;
        }
        return packets;
    }

    private static EnhancedPacket diagnosticPacket(byte[] file) {
        int offset = 0;
        while (offset < file.length) {
            int blockType = readIntLE(file, offset);
            int blockLength = readIntLE(file, offset + 4);
            if (blockType == ENHANCED_PACKET_BLOCK && readIntLE(file, offset + 8) == 1) {
                return readEnhancedPacket(file, offset, blockLength);
            }
            offset += blockLength;
        }
        return null;
    }

    private static EnhancedPacket readEnhancedPacket(byte[] file, int offset, int blockLength) {
        int interfaceId = readIntLE(file, offset + 8);
        int capturedLength = readIntLE(file, offset + 20);
        int originalLength = readIntLE(file, offset + 24);
        assertEquals(capturedLength, originalLength);
        int packetOffset = offset + 28;
        byte[] payload = new byte[capturedLength];
        System.arraycopy(file, packetOffset, payload, 0, capturedLength);
        int optionsOffset = packetOffset + paddedLength(capturedLength);
        String comment = findOptionString(file, optionsOffset, offset + blockLength - 4, 1);
        return new EnhancedPacket(interfaceId, payload, comment);
    }

    private static void assertValidIpv4TcpPacket(byte[] packet) {
        assertTrue(packet.length >= 40);
        assertEquals(4, (packet[0] >>> 4) & 0x0f);
        int ipHeaderLength = (packet[0] & 0x0f) * 4;
        assertEquals(20, ipHeaderLength);
        assertEquals(packet.length, readUnsignedShortBE(packet, 2));
        assertEquals(6, packet[9] & 0xff);
        assertEquals(0, internetChecksum(packet, 0, ipHeaderLength));

        int tcpLength = packet.length - ipHeaderLength;
        long sum = 0;
        sum = addNetworkWords(sum, packet, 12, 8);
        sum += 6;
        sum += tcpLength;
        sum = addNetworkWords(sum, packet, ipHeaderLength, tcpLength);
        assertEquals(0, finishInternetChecksum(sum));
    }

    private static void assertValidIpv6TcpPacket(byte[] packet) {
        assertTrue(packet.length >= 60);
        assertEquals(6, (packet[0] >>> 4) & 0x0f);
        assertEquals(packet.length - 40, readUnsignedShortBE(packet, 4));
        assertEquals(6, packet[6] & 0xff);

        int tcpLength = packet.length - 40;
        long sum = 0;
        sum = addNetworkWords(sum, packet, 8, 32);
        sum += (tcpLength >>> 16) & 0xffff;
        sum += tcpLength & 0xffff;
        sum += 6;
        sum = addNetworkWords(sum, packet, 40, tcpLength);
        assertEquals(0, finishInternetChecksum(sum));
    }

    private static void assertTcpPacket(byte[] packet, int sourcePort, int destinationPort,
            int expectedFlags, byte[] expectedPayload) {
        int tcpOffset = tcpOffset(packet);
        int tcpHeaderLength = ((packet[tcpOffset + 12] >>> 4) & 0x0f) * 4;
        assertEquals(20, tcpHeaderLength);
        assertEquals(sourcePort, readUnsignedShortBE(packet, tcpOffset));
        assertEquals(destinationPort, readUnsignedShortBE(packet, tcpOffset + 2));
        assertEquals(expectedFlags, packet[tcpOffset + 13] & 0xff);

        int payloadOffset = tcpOffset + tcpHeaderLength;
        int payloadLength = packet.length - payloadOffset;
        if (expectedPayload == null) {
            assertEquals(0, payloadLength);
        } else {
            byte[] actualPayload = new byte[payloadLength];
            System.arraycopy(packet, payloadOffset, actualPayload, 0, payloadLength);
            assertArrayEquals(expectedPayload, actualPayload);
        }
    }

    private static int tcpOffset(byte[] packet) {
        int version = (packet[0] >>> 4) & 0x0f;
        return version == 6 ? 40 : (packet[0] & 0x0f) * 4;
    }

    private static int tcpFlags(byte[] packet) {
        return packet[tcpOffset(packet) + 13] & 0xff;
    }

    private static long tcpSequence(byte[] packet) {
        return readUnsignedIntBE(packet, tcpOffset(packet) + 4);
    }

    private static long tcpAcknowledgment(byte[] packet) {
        return readUnsignedIntBE(packet, tcpOffset(packet) + 8);
    }

    private static long add32(long value, long increment) {
        return (value + increment) & 0xffffffffL;
    }

    private static boolean hasSyntheticSectionComment(byte[] file, int blockOffset, int blockLength) {
        String comment = findOptionString(file, blockOffset + 24, blockOffset + blockLength - 4, 1);
        return comment != null
                && comment.contains("TcpTap")
                && comment.contains("TCP/IP headers")
                && comment.contains("sequence numbers")
                && comment.contains("packet boundaries")
                && comment.contains("timestamps")
                && comment.contains("diagnostic events")
                && comment.contains("not wire-captured");
    }

    private static String findOptionString(
            byte[] file, int optionOffset, int optionLimit, int expectedOptionCode) {
        while (optionOffset + 4 <= optionLimit) {
            int optionCode = readUnsignedShortLE(file, optionOffset);
            int optionLength = readUnsignedShortLE(file, optionOffset + 2);
            if (optionCode == 0) {
                return null;
            }
            int valueOffset = optionOffset + 4;
            if (valueOffset + optionLength > optionLimit) {
                return null;
            }
            if (optionCode == expectedOptionCode) {
                return new String(file, valueOffset, optionLength, StandardCharsets.UTF_8);
            }
            optionOffset = valueOffset + paddedLength(optionLength);
        }
        return null;
    }

    private static int paddedLength(int length) {
        return (length + 3) & ~3;
    }

    private static int internetChecksum(byte[] data, int offset, int length) {
        return finishInternetChecksum(addNetworkWords(0, data, offset, length));
    }

    private static long addNetworkWords(long sum, byte[] data, int offset, int length) {
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

    private static int finishInternetChecksum(long sum) {
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        return (int) (~sum) & 0xffff;
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static int readUnsignedShortLE(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int readUnsignedShortBE(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static long readUnsignedIntBE(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (long) (data[offset + 3] & 0xff);
    }

    private static final class AddressOnlySocket extends Socket {
        private final SocketAddress remoteAddress;

        private AddressOnlySocket(SocketAddress remoteAddress) {
            this.remoteAddress = remoteAddress;
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return remoteAddress;
        }
    }

    private static final class EnhancedPacket {
        private final int interfaceId;
        private final byte[] payload;
        private final String comment;

        private EnhancedPacket(int interfaceId, byte[] payload, String comment) {
            this.interfaceId = interfaceId;
            this.payload = payload;
            this.comment = comment;
        }
    }
}
