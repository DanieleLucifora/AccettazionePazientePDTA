package it.uni.pdta.pdta_camunda.model;

public abstract class OperatoreSanitario {
    protected String id;
    protected String nome;
    protected String cognome;
    protected String unitaOperativa;
    protected String contatti;
    protected boolean disponibile;
    protected String funzioniPDTA;

    public OperatoreSanitario(String id, String nome, String cognome, String unitaOperativa) {
        this.id = id;
        this.nome = nome;
        this.cognome = cognome;
        this.unitaOperativa = unitaOperativa;
        this.disponibile = true;
        this.funzioniPDTA = "Non specificato";
    }

    public abstract String getRuolo();

    public String getFirma() {
        return getRuolo() + ": " + nome + " " + cognome + " (" + id + ")";
    }

    // Getters
    public String getId() { return id; }
    public String getNome() { return nome; }
    public String getCognome() { return cognome; }
    public String getUnitaOperativa() { return unitaOperativa; }
    public String getFunzioniPDTA() { return funzioniPDTA; }
    public void setFunzioniPDTA(String funzioniPDTA) { this.funzioniPDTA = funzioniPDTA; }
}
