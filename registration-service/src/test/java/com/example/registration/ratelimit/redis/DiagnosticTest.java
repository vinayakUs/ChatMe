package com.example.registration.ratelimit.redis;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Throwaway diagnostic: runs the script directly so the real exception escapes
 * checkRateLimit's catch block. Delete once the cause is known.
 */
class DiagnosticTest {

    @Test
    void showRealError() throws Exception {
        final YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        yaml.afterPropertiesSet();
        final var props = yaml.getObject();

        final LettuceConnectionFactory factory =
                new LettuceConnectionFactory(props.getProperty("spring.data.redis.host"),
                        Integer.parseInt(props.getProperty("spring.data.redis.port")));
        factory.setPassword(props.getProperty("spring.data.redis.password"));
        factory.afterPropertiesSet();

        final StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();

        // what is actually on the classpath?
        try (InputStream in = new ClassPathResource("validate-ratelimit.lua").getInputStream()) {
            final String src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("DIAG classpath bytes=" + src.length());
            for (String line : src.split("\n")) {
                if (line.contains("ttlMillis") || line.contains("max(")) {
                    System.out.println("DIAG lua| " + line);
                }
            }
        }

        final String key = "rl:diag:probe";
        redis.delete(key);

        final RedisScript<Long> script =
                RedisScript.of(new ClassPathResource("validate-ratelimit.lua"), Long.class);

        final String[] argv = { "3", "1000", "0", "1700000000000", "true" };
        try {
            final Long r = redis.execute(script, List.of(key), (Object[]) argv);
            System.out.println("DIAG consume OK, returned " + r);
            System.out.println("DIAG key now = " + redis.opsForHash().entries(key));
        } catch (Exception e) {
            System.out.println("DIAG consume THREW: " + e.getClass().getName());
            System.out.println("DIAG message: " + e.getMessage());
            Throwable t = e;
            while (t.getCause() != null) {
                t = t.getCause();
                System.out.println("DIAG caused by: " + t.getClass().getName() + " -> " + t.getMessage());
            }
        }
        redis.delete(key);
        factory.destroy();
    }
}