package com.example.registration.ratelimit.redis.fixeddelay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.registration.grpc.MessageTransport;
import com.example.registration.session.FailedSendReason;
import com.example.registration.session.RegistrationSession;

@Component("send-sms-verification-code-per-session")
public class FixedDelaySendSmsSessionRateLimiter extends FixedDelayRegistrationSessionRateLimiter {

    public FixedDelaySendSmsSessionRateLimiter(Clock clock,
            @Value("${rate-limits.send-sms-verification-code.delays}") List<Duration> delays) {
        super(clock, delays);
    }

    @Override
    int getAttemptsCount(RegistrationSession session) {
        if (session.getRejectedTransportList().contains(MessageTransport.MESSAGE_TRANSPORT_SMS)) {
            return Integer.MAX_VALUE;
        }
        return (int) session.getRegistrationAttemptsList().stream()
                .filter(attempt -> attempt
                        .getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_SMS)
                .count()
                +
                (int) session.getFailedAttemptList().stream()
                        .filter(attempt -> attempt
                                .getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_SMS)
                        .filter(attempt -> attempt
                                .getFailedSendReason() != FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
                        .count();
    }

    @Override
    Optional<Instant> getLastAttemptTime(RegistrationSession session) {
        return Stream.concat(

                session.getRegistrationAttemptsList().stream().filter(
                        attempt -> attempt
                                .getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_SMS)
                        .map(attempt -> Instant
                                .ofEpochMilli(attempt.getTimestampEpochMillis())),

                session.getFailedAttemptList().stream()
                        .filter(attempt -> attempt
                                .getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_SMS)
                        .filter(attempt -> attempt
                                .getFailedSendReason() != FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
                        .map(attempt -> Instant
                                .ofEpochMilli(attempt.getTimestampEpochMillis())))
                .max(Comparator.naturalOrder());
    }

}
