#!/bin/bash

# Script per avviare CLI Specialista (Chirurgo / Oncologo)
# Gestisce login e task di validazione

echo "Avvio CLI Specialista..."
echo ""

# Compila se necessario
./mvnw compile -q

# Esegui direttamente con java specificando la main class
./mvnw exec:java -Dexec.mainClass="it.uni.pdta.pdta_camunda.cli.SpecialistCli"
