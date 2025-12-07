package it.uni.pdta.pdta_camunda.worker;

import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.VariablesAsType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Worker automatico per la generazione del documento PDTA finale.
 * Aggrega i dati del paziente e le firme dei medici per creare un documento ufficiale.
 */
@Component
public class PdtaDocumentWorker {

    private static final Logger LOG = LoggerFactory.getLogger(PdtaDocumentWorker.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static class PdtaDocumentVars {
        public String patientID;
        public String patientData;
        public String diagnosiCode;
        public String gom1Signature;
        public String gom2Signature;
    }

    @JobWorker(type = "genera-pdta-doc")
    public Map<String, Object> generatePdtaDocument(@VariablesAsType PdtaDocumentVars vars) {
        LOG.info("Generazione documento PDTA per paziente={}, diagnosi={}", vars.patientID, vars.diagnosiCode);
        LOG.info("Dati: {}, Firme: GOM1={}, GOM2={}", vars.patientData, vars.gom1Signature, vars.gom2Signature);

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String documentId = String.format("PDTA-%s-%s", vars.patientID, timestamp);
        String pdtaDocumentUrl = String.format("/documents/pdta/%s.pdf", documentId);

        LOG.info("Documento PDTA generato: {} (ID: {})", pdtaDocumentUrl, documentId);

        return Map.of("pdtaDocumentUrl", pdtaDocumentUrl);
    }
}
