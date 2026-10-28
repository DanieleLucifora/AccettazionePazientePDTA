package it.uni.pdta.pdta_camunda.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class PathwayReadClientTests {
    @Test
    void rootVariablesArePagedAndTruncatedValuesAreRetrievedInFull() throws Exception {
        var template = new RestTemplate();
        var server = MockRestServiceServer.bindTo(template).build();
        var first = IntStream.range(0, 100).mapToObj(index -> Map.of("name", "v" + index, "scopeKey", "42",
                "value", String.valueOf(index))).toList();
        server.expect(requestTo("http://camunda/v2/variables/search")).andExpect(content().json("""
                {"filter":{"processInstanceKey":"42","scopeKey":"42"},"page":{"from":0,"limit":100}}
                """)).andRespond(withSuccess(new ObjectMapper().writeValueAsString(Map.of("items", first)), APPLICATION_JSON));
        server.expect(requestTo("http://camunda/v2/variables/search")).andExpect(content().json("""
                {"page":{"from":100,"limit":100}}
                """)).andRespond(withSuccess("""
                {"items":[
                  {"name":"patientData","scopeKey":"42","value":"truncated","isTruncated":true,"variableKey":"71"},
                  {"name":"patientData","scopeKey":"99","value":"\\"wrong local value\\""}
                ]}
                """, APPLICATION_JSON));
        server.expect(requestTo("http://camunda/v2/variables/71")).andRespond(withSuccess("""
                {"name":"patientData","scopeKey":"42","value":"\\"Paziente \\\\\\"Fittizio\\\\\\"\\""}
                """, APPLICATION_JSON));
        var vars = new CamundaRestClient("http://camunda/v2", template).getProcessVariables("42");
        assertThat(vars).hasSize(101).containsEntry("patientData", "Paziente \"Fittizio\"");
        server.verify();
    }

    @Test
    void invalidVariableOrElementResponseIsNotAnEmptySuccessfulHistory() {
        for (boolean variables : List.of(true, false)) {
            var template = new RestTemplate();
            var server = MockRestServiceServer.bindTo(template).build();
            server.expect(anything()).andRespond(withSuccess("{}", APPLICATION_JSON));
            var client = new CamundaRestClient("http://camunda/v2", template);
            assertThatThrownBy(() -> {
                if (variables) client.getProcessVariables("42"); else client.getProcessElements("42");
            }).isInstanceOf(IllegalStateException.class);
            server.verify();
        }
    }

    @Test
    void elementHistoryContinuesPastOneHundredRowsEvenWhenCountFlagIsFalse() throws Exception {
        var template = new RestTemplate();
        var server = MockRestServiceServer.bindTo(template).build();
        var first = IntStream.range(0, 100).mapToObj(index -> Map.of("elementInstanceKey", String.valueOf(index))).toList();
        server.expect(anything()).andRespond(withSuccess(new ObjectMapper().writeValueAsString(
                Map.of("items", first, "page", Map.of("hasMoreTotalItems", false))), APPLICATION_JSON));
        server.expect(anything()).andExpect(content().json("""
                {"filter":{"processInstanceKey":"42"},"page":{"from":100,"limit":100}}
                """)).andRespond(withSuccess("{\"items\":[{\"elementInstanceKey\":\"last\"}]}", APPLICATION_JSON));
        assertThat(new CamundaRestClient("http://camunda/v2", template).getProcessElements("42")).hasSize(101);
        server.verify();
    }
}
