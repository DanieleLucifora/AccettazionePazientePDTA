package it.uni.pdta.pdta_camunda.model;

public class Chirurgo extends Medico {

    public Chirurgo(String id, String nome, String cognome, String unitaOperativa, String specializzazione) {
        super(id, nome, cognome, unitaOperativa, specializzazione);
        this.funzioniPDTA = "Valutazione chirurgica e firma consenso";
    }

    @Override
    public String getRuolo() {
        return "Chirurgo " + specializzazione;
    }
}
