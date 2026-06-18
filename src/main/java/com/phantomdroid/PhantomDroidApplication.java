package com.phantomdroid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class PhantomDroidApplication {

    public static void main(String[] args) {
        SpringApplication.run(PhantomDroidApplication.class, args);
    }
}
