package com.g1739.immersiveaircraftcruise.cruise;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.CRC32;

public final class CruiseChunkPayloadCodec {
    public static final int MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024;

    private CruiseChunkPayloadCodec() {
    }

    public static byte[] encode(Packet<?> packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    public static byte[] compress(byte[] bytes) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(bytes.length);
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
                deflater.write(bytes);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to compress cruise chunk payload", exception);
        }
    }

    public static byte[] decompress(byte[] bytes) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(bytes.length * 2,
                    MAX_DECOMPRESSED_BYTES));
            try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = inflater.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_DECOMPRESSED_BYTES) {
                        throw new IllegalArgumentException("Cruise chunk payload exceeds decompressed size limit");
                    }
                    output.write(buffer, 0, read);
                }
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to decompress cruise chunk payload", exception);
        }
    }

    public static long hash(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }
}
