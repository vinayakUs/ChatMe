package com.example.registration.session;

import javax.annotation.Nonnull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the {@code bigtable.*} properties that locate the Bigtable instance this
 * service talks to, and how to reach it.
 */
@ConfigurationProperties(prefix = "bigtable")
public record BigtableProperties(
        @Nonnull @DefaultValue("chatme-local") String projectId,
        @Nonnull @DefaultValue("chatme-local") String instanceId,
        @DefaultValue("registration-sessions") String tableId,
        Emulator emulator) {
    /**
     * Connection details for the Cloud Bigtable emulator. Only consulted when
     * {@link #emulator()} reports {@code enabled}, in which case the client skips
     * application default credentials entirely.
     */
    public record Emulator(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("localhost") String host,
            @DefaultValue("8086") int port) {}
}