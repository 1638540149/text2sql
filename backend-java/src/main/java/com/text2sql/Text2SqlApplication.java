package com.text2sql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Text2SqlApplication {
    public static void main(String[] args) {
        SpringApplication.run(Text2SqlApplication.class, args);
    }
}
