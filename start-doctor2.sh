#!/bin/bash

# Script per avviare CLI Medico 2 (GOM2)
# Rimane in attesa (polling) e gestisce task "Revisione Medico GOM 2"
# Usa profilo 'cli' per disabilitare worker Camunda

echo "Avvio CLI Medico 2 (GOM2)..."
echo ""

./mvnw spring-boot:run \
  -Dspring-boot.run.main-class=it.uni.pdta.pdta_camunda.cli.Doctor2Cli \
  -Dspring-boot.run.arguments="--spring.profiles.active=cli"
