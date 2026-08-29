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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

final class PcapNgEncoder implements Closeable {
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

    private static final String CAPTURE_COMMENT =
            "TcpTap capture: interface 0 contains reconstructed synthetic TCP/IP packets; "
                    + "TCP/IP headers, sequence numbers, packet boundaries, and timestamps are "
                    + "synthetic representation values, not wire-captured values. Interface 1 "
                    + "contains TcpTap diagnostic events, also not wire-captured packets.";
    private static final String RECONSTRUCTED_INTERFACE_NAME = "tcptap-reconstructed";
    private static final String DIAGNOSTIC_INTERFACE_NAME = "tcptap-diagnostics";

    private final OutputStream output;
    private boolean failed;

    PcapNgEncoder(File file) throws IOException {
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

    void writeReconstructedPacket(long sessionId, byte[] packet, long timestampMicros) {
        writeEnhancedPacket(sessionId, RECONSTRUCTED_INTERFACE_ID, packet, timestampMicros, null);
    }

    void writeDiagnostic(long sessionId, String payload, String comment) {
        writeEnhancedPacket(
                sessionId,
                DIAGNOSTIC_INTERFACE_ID,
                payload.getBytes(StandardCharsets.UTF_8),
                epochMicros(),
                comment);
    }

    @Override
    public synchronized void close() throws IOException {
        output.close();
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

    static long epochMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
    }
}
