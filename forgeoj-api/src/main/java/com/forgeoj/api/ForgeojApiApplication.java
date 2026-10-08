package com.forgeoj.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ForgeojApiApplication {

	public static void main(String[] args) {
		if(args.length>0 && args[0].startsWith("admin-")) {
			System.exit(com.forgeoj.api.admin.AdminCli.run(args));
			return;
		}
		SpringApplication.run(ForgeojApiApplication.class, args);
	}

}
