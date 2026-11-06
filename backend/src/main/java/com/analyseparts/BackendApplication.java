package com.analyseparts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Monolithe modulaire : chaque package de premier niveau ({@code company}, {@code ingestion}…)
 * est un module Spring Modulith. Voir docs/adr/0001-monolithe-modulaire.md.
 */
@SpringBootApplication
@Modulithic(systemName = "Analyse Parts")
@EnableAsync
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
