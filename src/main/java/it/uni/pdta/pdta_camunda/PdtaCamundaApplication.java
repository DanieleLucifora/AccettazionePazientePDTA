package it.uni.pdta.pdta_camunda;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(
	basePackages = "it.uni.pdta.pdta_camunda",
	excludeFilters = @ComponentScan.Filter(
		type = FilterType.REGEX,
		pattern = "it\\.uni\\.pdta\\.pdta_camunda\\.cli\\..*"
	)
)
public class PdtaCamundaApplication {

	public static void main(String[] args) {
		SpringApplication.run(PdtaCamundaApplication.class, args);
	}

}
