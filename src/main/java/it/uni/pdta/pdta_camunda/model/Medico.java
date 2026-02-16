package it.uni.pdta.pdta_camunda.model;

public abstract class Medico extends OperatoreSanitario {
    protected String specializzazione;

    public Medico(String id, String nome, String cognome, String unitaOperativa, String specializzazione) {
        super(id, nome, cognome, unitaOperativa);
        this.specializzazione = specializzazione;
    }

    public String getSpecializzazione() {
        return specializzazione;
    }
}
