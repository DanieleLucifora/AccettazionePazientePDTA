package it.uni.pdta.pdta_camunda;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan("it.uni.pdta.pdta_camunda.scheduling.config")
public class PdtaCamundaApplication {

	public static void main(String[] args) {
		SpringApplication.run(PdtaCamundaApplication.class, args);
	}

}
