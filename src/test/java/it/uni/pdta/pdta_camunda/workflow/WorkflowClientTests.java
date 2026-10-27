package it.uni.pdta.pdta_camunda.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class WorkflowClientTests {
    @Test
    void variableValuesPreserveTypesAndEmbeddedQuotes() throws Exception {
        var template = new RestTemplate();
        var server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("http://camunda/v2/user-tasks/42/variables/search"))
                .andRespond(withSuccess("""
                        {"items":[
                          {"name":"patientData","value":"\\"Mario \\\\\\"Rossi\\\\\\"\\""},
                          {"name":"requiresExam","value":"true"},
                          {"name":"count","value":"3"}
                        ]}
                        """, APPLICATION_JSON));
        var client = new CamundaRestClient("http://camunda/v2", template);
        assertThat(client.getTypedTaskVariables("42")).containsEntry("patientData", "Mario \"Rossi\"")
                .containsEntry("requiresExam", true).containsEntry("count", 3);
        server.verify();
    }

    @Test
    void invalidVariableResponseIsAnErrorNotEmptyData() {
        var template = new RestTemplate();
        var server = MockRestServiceServer.bindTo(template).build();
        server.expect(anything()).andRespond(withSuccess("{}", APPLICATION_JSON));
        assertThatThrownBy(() -> new CamundaRestClient("http://camunda/v2", template).getTypedTaskVariables("42"))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void typedVariablesReadAllPages() throws Exception {
        var template = new RestTemplate();
        var server = MockRestServiceServer.bindTo(template).build();
        var firstPage = IntStream.range(0, 100)
                .mapToObj(index -> Map.of("name", "variable" + index, "value", String.valueOf(index))).toList();
        server.expect(anything()).andExpect(content().json("""
                {"page":{"from":0,"limit":100}}
                """)).andRespond(withSuccess(new ObjectMapper().writeValueAsString(Map.of("items", firstPage)),
                APPLICATION_JSON));
        server.expect(anything()).andExpect(content().json("""
                {"page":{"from":100,"limit":100}}
                """)).andRespond(withSuccess("""
                {"items":[{"name":"patientID","value":"\\"001\\""}]}
                """, APPLICATION_JSON));
        var result = new CamundaRestClient("http://camunda/v2", template).getTypedTaskVariables("42");
        assertThat(result).hasSize(101).containsEntry("patientID", "001");
        server.verify();
    }

    @Test
    void taskListFiltersRoleAndStateBeforeLoadingPatientData() throws Exception {
        var rest = mock(CamundaRestClient.class);
        var surgeon = task("1", "CREATED", "CHIRURGHI");
        var oncologist = task("2", "CREATED", "ONCOLOGI");
        var completed = task("3", "COMPLETED", "CHIRURGHI");
        when(rest.searchTasks(anyMap())).thenReturn(List.of(surgeon, oncologist, completed));
        when(rest.getTypedTaskVariables("1")).thenReturn(Map.of("patientID", "001", "patientData", "Mario"));
        var result = new SpecialistTaskService(rest).findTasks(SpecialistTaskService.SpecialistRole.CHIRURGHI);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().patientID()).isEqualTo("001");
        verify(rest, never()).getTypedTaskVariables("2");
        verify(rest, never()).getTypedTaskVariables("3");
    }

    private CamundaRestClient.UserTask task(String key, String state, String group) {
        var task = new CamundaRestClient.UserTask();
        task.userTaskKey = key;
        task.state = state;
        task.candidateGroups = List.of(group);
        return task;
    }
}
