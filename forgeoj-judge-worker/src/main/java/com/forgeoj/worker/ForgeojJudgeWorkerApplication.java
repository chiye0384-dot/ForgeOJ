package com.forgeoj.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ForgeojJudgeWorkerApplication {

	public static void main(String[] args) {
		SpringApplication.run(ForgeojJudgeWorkerApplication.class, args);
	}

}
