#!/bin/bash

# Script per avviare CLI Segreteria/CaseManager
# Gestisce la registrazione nuovo paziente e task "Raccolta Dati Accesso"
# Applicazione Java standalone senza Spring Boot

echo "Avvio CLI Segreteria/CaseManager..."
echo ""

# Compila se necessario
./mvnw compile -q

# Esegui direttamente con java specificando la main class
./mvnw exec:java -Dexec.mainClass="it.uni.pdta.pdta_camunda.cli.SecretaryCli"
