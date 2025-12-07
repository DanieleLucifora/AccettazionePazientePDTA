#!/bin/bash

# Script per avviare l'applicazione Spring Boot principale
# Gestisce i worker automatici (@JobWorker)

echo "Avvio applicazione Spring Boot PDTA..."
echo ""

./mvnw spring-boot:run -Dspring-boot.run.main-class=it.uni.pdta.pdta_camunda.PdtaCamundaApplication
