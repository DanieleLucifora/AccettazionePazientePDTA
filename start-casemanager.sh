#!/bin/bash

# Script per avviare CLI Case Manager
# Gestisce la registrazione nuovo paziente e task "Raccolta Dati Accesso"

echo "Avvio CLI Case Manager..."
echo ""

# Compila se necessario
./mvnw compile -q

# Esegui direttamente con java specificando la main class
./mvnw exec:java -Dexec.mainClass="it.uni.pdta.pdta_camunda.cli.CaseManagerCli"
