package com.example.registration.session;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.BigtableDataSettings;

@Configuration
public class BigtableConfig {

    @Bean
    public BigtableDataClient bigtableDataClient(BigtableProperties properties) throws IOException {
        BigtableDataSettings.Builder settings = properties.emulator().enabled()
                ? BigtableDataSettings.newBuilderForEmulator(
                        properties.emulator().host(), properties.emulator().port())
                : BigtableDataSettings.newBuilder();

        return BigtableDataClient.create(settings.setProjectId(properties.projectId())
                .setInstanceId(properties.instanceId())
                .build());
    }
}
