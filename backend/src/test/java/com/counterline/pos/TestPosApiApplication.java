package com.counterline.pos;

import org.springframework.boot.SpringApplication;

public class TestPosApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(PosApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
