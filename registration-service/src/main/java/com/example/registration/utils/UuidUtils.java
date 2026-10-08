package com.example.registration.utils;

import java.nio.ByteBuffer;
import java.util.UUID;
import com.google.protobuf.ByteString;

public class UuidUtils {
    public static ByteString uuidToByteString(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);

        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());

        return ByteString.copyFrom(buffer.array());
    }

    public static UUID byteStringToUuid(ByteString bytes) {
        if (bytes.size() != 16) {
            throw new IllegalArgumentException("UUID must be exactly 16 bytes, but got " + bytes.size());
        }

        ByteBuffer buffer = bytes.asReadOnlyByteBuffer();

        long mostSignificantBits = buffer.getLong();
        long leastSignificantBits = buffer.getLong();

        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
