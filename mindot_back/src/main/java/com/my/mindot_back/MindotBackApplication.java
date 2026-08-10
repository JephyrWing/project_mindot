package com.my.mindot_back;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class MindotBackApplication {

	public static void main(String[] args) {
		SpringApplication.run(MindotBackApplication.class, args);
	}

}
