package com.forgeoj.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.flyway.enabled=false", "forgeoj.self-test.cleanup.enabled=false"})
class ForgeojApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
