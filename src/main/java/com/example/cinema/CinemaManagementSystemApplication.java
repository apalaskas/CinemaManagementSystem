package com.example.cinema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CinemaManagementSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(CinemaManagementSystemApplication.class, args);
    }
}
