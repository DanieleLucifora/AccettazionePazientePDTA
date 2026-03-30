package it.uni.pdta.pdta_camunda.model;

public class TecnicoLaboratorio extends OperatoreSanitario {
    private String laboratorio;

    public TecnicoLaboratorio(String id, String nome, String cognome, String unitaOperativa) {
        super(id, nome, cognome, unitaOperativa);
        this.laboratorio = unitaOperativa;
    }

    @Override
    public String getRuolo() {
        return "Tecnico Laboratorio";
    }

    public String getLaboratorio() {
        return laboratorio;
    }

    public void setLaboratorio(String laboratorio) {
        this.laboratorio = laboratorio;
    }
}
