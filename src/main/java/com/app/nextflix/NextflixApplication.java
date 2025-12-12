package com.app.nextflix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NextflixApplication {

	public static void main(String[] args) {
		SpringApplication.run(NextflixApplication.class, args);
	}

}
