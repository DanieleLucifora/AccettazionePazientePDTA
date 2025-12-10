#!/bin/bash

# Script per avviare CLI Medico 2 (GOM2)
# Rimane in attesa (polling) e gestisce task "Revisione Medico GOM 2"
# Applicazione Java standalone senza Spring Boot

echo "Avvio CLI Medico 2 (GOM2)..."
echo ""

# Compila se necessario
./mvnw compile -q

# Esegui direttamente con java specificando la main class
./mvnw exec:java -Dexec.mainClass="it.uni.pdta.pdta_camunda.cli.Doctor2Cli"
