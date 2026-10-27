package it.uni.pdta.pdta_camunda;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1",
	"camunda.client.worker.defaults.enabled=false",
	"pdta.kpi.enabled=false"
})
@AutoConfigureMockMvc
class PdtaCamundaApplicationTests {

	@Autowired MockMvc mvc;

	@Test
	void contextLoads() {
	}

	@Test
	void demoApiIsNotAvailableWithoutWebUiProfile() throws Exception {
		mvc.perform(get("/api/tasks").param("role", "CHIRURGHI")).andExpect(status().isNotFound());
	}

}
