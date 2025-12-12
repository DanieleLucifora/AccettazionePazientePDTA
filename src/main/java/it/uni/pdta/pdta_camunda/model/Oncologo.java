package it.uni.pdta.pdta_camunda.model;

public class Oncologo extends Medico {
    
    public Oncologo(String id, String nome, String cognome, String unitaOperativa) {
        super(id, nome, cognome, unitaOperativa, "Oncologia Medica");
        this.funzioniPDTA = "Valutazione oncologica e stadiazione";
    }

    @Override
    public String getRuolo() {
        return "Oncologo Medico";
    }
}
