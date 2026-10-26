package com.example.registration;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RegistrationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RegistrationServiceApplication.class, args);
    }

    @Value("${rate-limits.send-sms-verification-code.delays}")
    private List<Duration> delays;


    @Bean
CommandLineRunner commandLineRunner(Environment env) {
    return args -> {
        System.out.println(
            "Delay 0 = " +
            env.getProperty(
                "rate-limits.send-sms-verification-code.delays[0]"
            )
        );

        System.out.println(
            "Delay 1 = " +
            env.getProperty(
                "rate-limits.send-sms-verification-code.delays[1]"
            )
        );
                System.out.println("SMS verification delays: " + delays);
        delays.forEach(d -> System.out.println(d + " -> " + d.toMinutes() + " min"));

    };
}
}
