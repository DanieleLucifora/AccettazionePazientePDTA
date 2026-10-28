package it.uni.pdta.pdta_camunda.cli;

import io.camunda.client.CamundaClient;
import it.uni.pdta.pdta_camunda.model.CaseManager;
import it.uni.pdta.pdta_camunda.registration.PatientRegistrationService;
import it.uni.pdta.pdta_camunda.registration.RegistrationRequest;
import it.uni.pdta.pdta_camunda.workflow.CamundaRestClient;

import java.net.URI;
import java.util.Scanner;

/**
 * CLI Case Manager (ex Segreteria)
 *
 * Comportamento:
 * 1. Login simulato come Case Manager
 * 2. Chiede se vuoi inserire nuovo paziente
 * 3. Raccoglie dati paziente interattivamente
 * 4. Avvia processo PDTA tramite CamundaClient
 * 5. Cerca task "Raccolta Dati Accesso" tramite API REST v2
 * 6. Assegna e completa task automaticamente
 *
 */
public class CaseManagerCli {

    private final CamundaClient camundaClient;
    private final PatientRegistrationService registrationService;
    private final CaseManager currentUser;

    public CaseManagerCli() {
        // Crea CamundaClient manualmente per CLI
        this.camundaClient = CamundaClient.newClientBuilder()
            .grpcAddress(URI.create(CliEndpoints.grpcAddress()))
            .restAddress(URI.create(CliEndpoints.restAddress()))
                .build();
        this.registrationService = new PatientRegistrationService(camundaClient,
                new CamundaRestClient(CliEndpoints.restV2BaseUrl()));
        
        // Login simulato
        this.currentUser = new CaseManager("CM001", "Giulia", "Verdi");
    }

    public static void main(String[] args) throws Exception {
        CaseManagerCli cli = new CaseManagerCli();
        cli.run(args);
    }

    public void run(String... args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        printHeader();

        System.out.println("Benvenuto, " + currentUser.getRuolo() + " " + currentUser.getNome() + " " + currentUser.getCognome());
        System.out.println("Unità Operativa: " + currentUser.getUnitaOperativa());
        System.out.println();

        boolean continueLoop = true;
        while (continueLoop) {
            System.out.print("\nVuoi inserire un nuovo paziente nel PDTA? [y/N]: ");
            String confirm = scanner.nextLine().trim();
            if (!confirm.equalsIgnoreCase("y")) {
                System.out.println("\n⚠ Operazione annullata.");
                break;
            }

            // Raccolta dati paziente
            System.out.println("\n" + colorize("=== RACCOLTA DATI PAZIENTE ===", Color.GREEN));
            System.out.println();

            System.out.print("Patient ID (es. 070686): ");
            String patientID = scanner.nextLine().trim();
            if (patientID.isEmpty()) {
                System.err.println("✗ Patient ID obbligatorio");
                continue;
            }

            System.out.print("Nome: ");
            String firstName = scanner.nextLine().trim();
            System.out.print("Cognome: ");
            String lastName = scanner.nextLine().trim();
            System.out.print("Data di nascita (AAAA-MM-GG): ");
            java.time.LocalDate birthDate;
            try {
                birthDate = java.time.LocalDate.parse(scanner.nextLine().trim());
            } catch (java.time.format.DateTimeParseException exception) {
                System.err.println("Data di nascita non valida.");
                continue;
            }

            System.out.println("Codici diagnosi previsti: " + it.uni.pdta.pdta_camunda.registration.DiagnosisCatalog.codes());
            System.out.print("Codice Diagnosi: ");
            String diagnosiCode = scanner.nextLine().trim();
            if (diagnosiCode.isEmpty()) {
                System.err.println("✗ Codice diagnosi obbligatorio");
                continue;
            }

            System.out.print("URL Cartella Clinica [opzionale]: ");
            String clinicalFileUrl = scanner.nextLine().trim();

            // Riepilogo
            System.out.println("\n" + colorize("=== RIEPILOGO DATI ===", Color.YELLOW));
            System.out.println("Patient ID: " + patientID);
            System.out.println("Paziente: " + firstName + " " + lastName + " - nascita: " + birthDate);
            System.out.println("Codice Diagnosi: " + diagnosiCode);
            System.out.println("URL Cartella: " + (clinicalFileUrl.isEmpty() ? "[non fornito]" : clinicalFileUrl));
            System.out.println();

            System.out.print("Confermi i dati e vuoi avviare il processo? [Y/n]: ");
            String confirmStart = scanner.nextLine().trim();
            if (confirmStart.equalsIgnoreCase("n")) {
                System.out.println("\n⚠ Operazione annullata.");
                continue;
            }

            try {
                System.out.print("Avvio processo 'presa_in_carico_pdta'... ");
                var progress = registrationService.start(
                        new RegistrationRequest(patientID, firstName, lastName, birthDate, diagnosiCode, clinicalFileUrl),
                        currentUser.getId());
                System.out.println(colorize("Processo avviato con successo!", Color.GREEN));
                System.out.println("Process Instance Key: " + progress.processInstanceKey());
                System.out.print("Completamento raccolta dati accesso... ");
                for (int i = 0; i < 10; i++) {
                    progress = registrationService.completeAccess(progress.processInstanceKey(), currentUser.getId());
                    if (progress.status() == PatientRegistrationService.AccessStatus.COMPLETED) break;
                    Thread.sleep(1000);
                }
                if (progress.status() != PatientRegistrationService.AccessStatus.COMPLETED) {
                    System.err.println("Task non ancora disponibile. Il processo esiste gia': non reinserire il paziente.");
                    System.err.println("Verifica su Operate: " + CliEndpoints.operateUrl());
                    continue;
                }
                System.out.println(colorize("✓ Task 'Raccolta Dati Accesso' completato con successo!", Color.GREEN));
                System.out.println("\n Paziente inserito correttamente.");

                // Chiedi se continuare con un altro paziente
                System.out.print("\nVuoi inserire un altro paziente? [y/N]: ");
                String again = scanner.nextLine().trim();
                continueLoop = again.equalsIgnoreCase("y");
            } catch (Exception e) {
                System.err.println("\n✗ Errore durante l'esecuzione: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        scanner.close();
        System.exit(0);
    }

    private void printHeader() {
        System.out.println(colorize("╔════════════════════════════════════════════════╗", Color.CYAN));
        System.out.println(colorize("║              PDTA - CASE MANAGER CLI           ║", Color.CYAN));
        System.out.println(colorize("╚════════════════════════════════════════════════╝", Color.CYAN));
        System.out.println();
    }

    // Utility colori console
    enum Color { RED, GREEN, YELLOW, BLUE, CYAN, RESET }
    private String colorize(String text, Color color) {
        String code = switch (color) {
            case RED -> "\u001B[31m";
            case GREEN -> "\u001B[32m";
            case YELLOW -> "\u001B[33m";
            case BLUE -> "\u001B[34m";
            case CYAN -> "\u001B[36m";
            default -> "\u001B[0m";
        };
        return code + text + "\u001B[0m";
    }
}
