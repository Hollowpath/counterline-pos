package com.counterline.pos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PosApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(PosApiApplication.class, args);
	}

}
