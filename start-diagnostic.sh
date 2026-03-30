#!/bin/bash

# Script per avviare CLI Diagnostica
# Gestisce le task "Esami Diagnostici" del processo

echo "Avvio CLI Diagnostica (Laboratorio/Radiologia)..."
echo ""

# Compila se necessario
./mvnw compile -q

# Esegui direttamente con java specificando la main class
./mvnw exec:java -Dexec.mainClass="it.uni.pdta.pdta_camunda.cli.DiagnosticCli"
