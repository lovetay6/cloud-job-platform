package io.cloudjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CloudJobApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudJobApplication.class, args);
    }
}
