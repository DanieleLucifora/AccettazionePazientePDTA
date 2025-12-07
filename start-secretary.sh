#!/bin/bash

# Script per avviare CLI Segreteria/CaseManager
# Gestisce la registrazione nuovo paziente e task "Raccolta Dati Accesso"
# Usa profilo 'cli' per disabilitare worker Camunda

echo "Avvio CLI Segreteria/CaseManager..."
echo ""

./mvnw spring-boot:run \
  -Dspring-boot.run.main-class=it.uni.pdta.pdta_camunda.cli.SecretaryCli \
  -Dspring-boot.run.arguments="--spring.profiles.active=cli"
