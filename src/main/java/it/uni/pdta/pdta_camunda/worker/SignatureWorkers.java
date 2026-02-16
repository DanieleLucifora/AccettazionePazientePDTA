package it.uni.pdta.pdta_camunda.worker;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SignatureWorkers {

    private static final Logger logger = LoggerFactory.getLogger(SignatureWorkers.class);

    @JobWorker(type = "sign-document-chirurgo")
    public void signDocumentChirurgo(final JobClient client, final ActivatedJob job) {
        signDocument(client, job, "CHIRURGO");
    }

    @JobWorker(type = "sign-document-oncologo")
    public void signDocumentOncologo(final JobClient client, final ActivatedJob job) {
        signDocument(client, job, "ONCOLOGO");
    }

    private void signDocument(JobClient client, ActivatedJob job, String role) {
        Map<String, Object> variables = job.getVariablesAsMap();
        String patientID = (String) variables.get("patientID");
        
        logger.info("Firma digitale in corso per {} - Paziente: {}", role, patientID);

        // Simulazione logica di firma (es. generazione hash)
        String signature = "SIGNED_" + role + "_" + patientID + "_" + System.currentTimeMillis();
        
        // Determina i nomi delle variabili in base al ruolo
        String signedVarName;
        String signatureVarName;
        
        if ("CHIRURGO".equals(role)) {
            signedVarName = "chirurgoSigned";
            signatureVarName = "chirurgoSignature";
        } else {
            signedVarName = "oncologoSigned";
            signatureVarName = "oncologoSignature";
        }
        
        // Aggiungi firma e flag alle variabili
        variables.put(signedVarName, true);
        variables.put(signatureVarName, signature);

        logger.info("Documento firmato digitalmente da {}. Signature: {}", role, signature);

        client.newCompleteCommand(job.getKey())
                .variables(variables)
                .send()
                .join();
    }
}
