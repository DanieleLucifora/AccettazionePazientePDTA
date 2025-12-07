package it.uni.pdta.pdta_camunda.worker;

import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.VariablesAsType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Worker automatico per la gestione delle firme digitali dei medici GOM.
 * Questi worker vengono eseguiti automaticamente quando Camunda rileva job del tipo specificato.
 */
@Component
public class SignatureWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(SignatureWorkers.class);
    private static final String GOM1_SECRET = "GOM1-SECRET-123-";
    private static final String GOM2_SECRET = "GOM2-SECRET-123-";

    public static class PdtaVars {
        public String patientID;
        public String diagnosiCode;
    }

    @JobWorker(type = "sign-document-gom1")
    public Map<String, Object> signGom1(@VariablesAsType PdtaVars vars) {
        LOG.info("Firma digitale GOM1 per paziente={}, diagnosi={}", vars.patientID, vars.diagnosiCode);

        String signature = GOM1_SECRET + vars.patientID;
        return Map.of("gom1Signed", true, "gom1Signature", signature);
    }

    @JobWorker(type = "sign-document-gom2")
    public Map<String, Object> signGom2(@VariablesAsType PdtaVars vars) {
        LOG.info("Firma digitale GOM2 per paziente={}, diagnosi={}", vars.patientID, vars.diagnosiCode);

        String signature = GOM2_SECRET + vars.patientID;
        return Map.of("gom2Signed", true, "gom2Signature", signature);
    }
}
