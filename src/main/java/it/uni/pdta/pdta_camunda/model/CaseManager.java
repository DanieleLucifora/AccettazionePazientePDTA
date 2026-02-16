package it.uni.pdta.pdta_camunda.model;

public class CaseManager extends OperatoreSanitario {
    
    public CaseManager(String id, String nome, String cognome) {
        super(id, nome, cognome, "Coordinamento PDTA");
        this.funzioniPDTA = "Presa in carico e coordinamento percorso";
    }

    @Override
    public String getRuolo() {
        return "Case Manager";
    }
}
