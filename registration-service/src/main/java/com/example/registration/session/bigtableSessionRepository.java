package com.example.registration.session;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.springframework.stereotype.Repository;

import com.example.registration.utils.UuidUtils;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.models.RowMutation;
import com.google.cloud.bigtable.data.v2.models.TableId;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.google.protobuf.ByteString;

/**
 * bigtableSessionRepository
 */
@Repository
public class bigtableSessionRepository implements SessionRepository {

    private final BigtableDataClient bigtableDataClient;

    private static final @Nonnull ByteString DATA_COLUMN_NAME = Objects.requireNonNull(ByteString.copyFromUtf8("D"));

    private static final Duration REMOVAL_TTL_PADDING = Duration.ofMinutes(5);

    private static final @Nonnull ByteString REMOVAL_COLUMN_NAME = Objects.requireNonNull(ByteString.copyFromUtf8("R"));
    private final String columnFamilyName = "S";
    TableId tableId = TableId.of("registration-sessions");

    public bigtableSessionRepository(BigtableDataClient bigtableDataClient) {
        this.bigtableDataClient = bigtableDataClient;
    }

    @Override
    public RegistrationSession createSession(PhoneNumber e164, SessionMetadata sessionMetadata, Instant expiration) {

        final UUID sessionId = UUID.randomUUID();

        RegistrationSession registrationSession = RegistrationSession.newBuilder()
                .setId(UuidUtils.uuidToByteString(sessionId))
                .setPhoneNummber(PhoneNumberUtil.getInstance().format(e164, PhoneNumberFormat.E164))
                .build();

        ByteString expirationByteString = Objects.requireNonNull(ByteString.copyFrom(ByteBuffer.allocate(Long.BYTES)
                .putLong(expiration.plus(REMOVAL_TTL_PADDING).toEpochMilli())
                .array()));

        ByteString payload = Objects.requireNonNull(registrationSession.toByteString());

        bigtableDataClient.mutateRow(RowMutation.create(tableId, UuidUtils.uuidToByteString(sessionId))
                .setCell(columnFamilyName, DATA_COLUMN_NAME, payload)
                .setCell(columnFamilyName, REMOVAL_COLUMN_NAME, expirationByteString));

        return registrationSession;
    }
}
