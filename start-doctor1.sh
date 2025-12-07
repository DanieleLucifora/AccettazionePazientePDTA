#!/bin/bash

# Script per avviare CLI Medico 1 (GOM1)
# Rimane in attesa (polling) e gestisce task "Revisione Medico GOM 1"
# Usa profilo 'cli' per disabilitare worker Camunda

echo "Avvio CLI Medico 1 (GOM1)..."
echo ""

./mvnw spring-boot:run \
  -Dspring-boot.run.main-class=it.uni.pdta.pdta_camunda.cli.Doctor1Cli \
  -Dspring-boot.run.arguments="--spring.profiles.active=cli"
