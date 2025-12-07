package it.uni.pdta.pdta_camunda.controller;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import it.uni.pdta.pdta_camunda.dto.ApiResponse;
import it.uni.pdta.pdta_camunda.dto.CompleteTaskRequest;
import it.uni.pdta.pdta_camunda.dto.StartProcessRequest;
import it.uni.pdta.pdta_camunda.dto.StartProcessResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller per la gestione dei processi PDTA.
 *
 * Fornisce API REST per interagire con Camunda Platform 8:
 * - POST /api/pdta/start - Avvia nuovo processo PDTA
 * - POST /api/pdta/tasks/{jobKey}/complete - Completa user task
 * - GET /api/pdta/health - Health check del sistema
 * - GET /api/pdta/topology - Info cluster Camunda
 */
@RestController
@RequestMapping("/api/pdta")
public class PdtaProcessController {

    private static final Logger LOG = LoggerFactory.getLogger(PdtaProcessController.class);
    private final CamundaClient camundaClient;

    public PdtaProcessController(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
        LOG.info("PdtaProcessController initialized");
    }

    // Avvia un nuovo processo PDTA per un paziente
    @PostMapping("/start")
    public ResponseEntity<StartProcessResponse> startProcess(@RequestBody StartProcessRequest request) {

        LOG.info(">>> Starting PDTA process for patient: {}", request.getPatientID());
        LOG.debug("Request details: {}", request);

        try {
            // Prepara le variabili di processo
            Map<String, Object> variables = new HashMap<>();
            variables.put("patientID", request.getPatientID());
            variables.put("patientData", request.getPatientData());
            variables.put("diagnosiCode", request.getDiagnosiCode());


            ProcessInstanceEvent instance = camundaClient.newCreateInstanceCommand()
                    .bpmnProcessId("presa_in_carico_pdta")
                    .latestVersion()
                    .variables(variables)
                    .send()
                    .join();

            LOG.info(">>> Process started successfully. Instance key: {}", instance.getProcessInstanceKey());

            // Crea la risposta
            StartProcessResponse response = new StartProcessResponse(
                    instance.getProcessInstanceKey(),
                    instance.getBpmnProcessId(),
                    instance.getVersion()
            );

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (Exception e) {
            LOG.error("Error starting PDTA process", e);

            StartProcessResponse errorResponse = new StartProcessResponse();
            errorResponse.setStatus("ERROR");
            errorResponse.setMessage("Errore nell'avvio del processo: " + e.getMessage());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    // Completa un user task specificando il job key
    @PostMapping("/tasks/{jobKey}/complete")
    public ResponseEntity<ApiResponse> completeTask(
            @PathVariable Long jobKey,
            @RequestBody CompleteTaskRequest request) {

        LOG.info(">>> Completing task with jobKey: {}", jobKey);
        LOG.debug("Variables: {}", request.getVariables());

        try {
            camundaClient.newCompleteCommand(jobKey)
                    .variables(request.getVariables())
                    .send()
                    .join();

            LOG.info(">>> Task completed successfully: {}", jobKey);

            return ResponseEntity.ok(
                    ApiResponse.success("Task completato con successo",
                            Map.of("jobKey", jobKey))
            );

        } catch (Exception e) {
            LOG.error("Error completing task {}", jobKey, e);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Errore nel completamento del task: " + e.getMessage()));
        }
    }

    // Health check endpoint
    // GET /api/pdta/health
    @GetMapping("/health")
    public ResponseEntity<ApiResponse> health() {
        try {
            camundaClient.newTopologyRequest().send().join();
            return ResponseEntity.ok(ApiResponse.success("PDTA API is healthy"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Cannot connect to Camunda: " + e.getMessage()));
        }
    }

    @GetMapping("/topology")
    public ResponseEntity<ApiResponse> getTopology() {
        try {
            var topology = camundaClient.newTopologyRequest().send().join();

            Map<String, Object> topologyInfo = new HashMap<>();
            topologyInfo.put("clusterSize", topology.getClusterSize());
            topologyInfo.put("partitionsCount", topology.getPartitionsCount());
            topologyInfo.put("replicationFactor", topology.getReplicationFactor());
            topologyInfo.put("gatewayVersion", topology.getGatewayVersion());

            return ResponseEntity.ok(
                    ApiResponse.success("Zeebe topology retrieved", topologyInfo)
            );
        } catch (Exception e) {
            LOG.error("Error retrieving topology", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Cannot retrieve topology: " + e.getMessage()));
        }
    }
}
