package ru.kaiserroman.millenairearmies.network;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import net.minecraft.network.FriendlyByteBuf;

/** Small allocation-free codec helpers with explicit protocol limits. */
final class BoundedCodecs {
    static final int[] EMPTY_INTS = new int[0];
    static final long[] EMPTY_LONGS = new long[0];
    static final byte[] EMPTY_BYTES = new byte[0];

    private BoundedCodecs() {}

    static int readCount(FriendlyByteBuf buffer, int maximum, String field) {
        int value = buffer.readVarInt();
        if (value < 0 || value > maximum) {
            throw new DecoderException(field + " outside 0.." + maximum + ": " + value);
        }
        return value;
    }

    static void writeCount(FriendlyByteBuf buffer, int value, int maximum, String field) {
        if (value < 0 || value > maximum) {
            throw new EncoderException(field + " outside 0.." + maximum + ": " + value);
        }
        buffer.writeVarInt(value);
    }

    static long readRevision(FriendlyByteBuf buffer, String field) {
        long value = buffer.readVarLong();
        if (value < 0) {
            throw new DecoderException(field + " must be non-negative");
        }
        return value;
    }

    static void writeRevision(FriendlyByteBuf buffer, long value, String field) {
        if (value < 0) {
            throw new EncoderException(field + " must be non-negative");
        }
        buffer.writeVarLong(value);
    }

    static int readSignedVarInt(FriendlyByteBuf buffer) {
        int encoded = buffer.readVarInt();
        return (encoded >>> 1) ^ -(encoded & 1);
    }

    static void writeSignedVarInt(FriendlyByteBuf buffer, int value) {
        buffer.writeVarInt((value << 1) ^ (value >> 31));
    }

    static int[] readSignedInts(FriendlyByteBuf buffer, int count, int stride) {
        int length = checkedLength(count, stride);
        if (length == 0) {
            return EMPTY_INTS;
        }
        int[] values = new int[length];
        for (int index = 0; index < length; index++) {
            values[index] = readSignedVarInt(buffer);
        }
        return values;
    }

    static int[] readRawVarInts(FriendlyByteBuf buffer, int count, int stride) {
        int length = checkedLength(count, stride);
        if (length == 0) {
            return EMPTY_INTS;
        }
        int[] values = new int[length];
        for (int index = 0; index < length; index++) {
            values[index] = buffer.readVarInt();
        }
        return values;
    }

    static long[] readLongs(FriendlyByteBuf buffer, int count, int stride) {
        int length = checkedLength(count, stride);
        if (length == 0) {
            return EMPTY_LONGS;
        }
        long[] values = new long[length];
        for (int index = 0; index < length; index++) {
            values[index] = buffer.readLong();
        }
        return values;
    }

    static byte[] readBytes(FriendlyByteBuf buffer, int count, int stride) {
        int length = checkedLength(count, stride);
        if (length == 0) {
            return EMPTY_BYTES;
        }
        byte[] values = new byte[length];
        buffer.readBytes(values);
        return values;
    }

    static void writeSignedInts(FriendlyByteBuf buffer, int[] values) {
        for (int value : values) {
            writeSignedVarInt(buffer, value);
        }
    }

    static void writeRawVarInts(FriendlyByteBuf buffer, int[] values) {
        for (int value : values) {
            buffer.writeVarInt(value);
        }
    }

    static void writeLongs(FriendlyByteBuf buffer, long[] values) {
        for (long value : values) {
            buffer.writeLong(value);
        }
    }

    static void writeBytes(FriendlyByteBuf buffer, byte[] values) {
        buffer.writeBytes(values);
    }

    static String readUtf8(FriendlyByteBuf buffer, int maximumBytes, String field) {
        int length = readCount(buffer, maximumBytes, field + " UTF-8 bytes");
        if (length == 0) {
            return "";
        }
        byte[] encoded = new byte[length];
        buffer.readBytes(encoded);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new DecoderException(field + " is not valid UTF-8", exception);
        }
    }

    static int writeUtf8(FriendlyByteBuf buffer, String value, int maximumBytes, String field) {
        if (value == null) {
            throw new EncoderException(field + " must not be null");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeCount(buffer, encoded.length, maximumBytes, field + " UTF-8 bytes");
        buffer.writeBytes(encoded);
        return encoded.length;
    }

    static int utf8Length(String value, int maximumBytes, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length > maximumBytes) {
            throw new IllegalArgumentException(
                    field + " exceeds " + maximumBytes + " UTF-8 bytes: " + length);
        }
        return length;
    }

    static int checkedLength(int count, int stride) {
        try {
            return Math.multiplyExact(count, stride);
        } catch (ArithmeticException exception) {
            throw new DecoderException("Primitive column length overflow", exception);
        }
    }
}
