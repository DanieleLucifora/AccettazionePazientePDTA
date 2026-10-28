package it.uni.pdta.pdta_camunda.registration;

import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.util.ArrayList;
import java.util.List;

public final class DiagnosisCatalog {
    private static final List<String> CODES = load();

    private DiagnosisCatalog() {}

    public static List<String> codes() { return CODES; }

    private static List<String> load() {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try (var input = DiagnosisCatalog.class.getResourceAsStream("/verifica-criteri-inclusione.dmn")) {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            if (input == null) throw new IllegalStateException("Catalogo diagnosi DMN assente.");
            var document = factory.newDocumentBuilder().parse(input);
            var rules = document.getElementsByTagNameNS("*", "rule");
            List<String> codes = new ArrayList<>();
            for (int i = 0; i < rules.getLength(); i++) {
                var rule = (Element) rules.item(i);
                var texts = ((Element) rule.getElementsByTagNameNS("*", "inputEntry").item(0))
                        .getElementsByTagNameNS("*", "text");
                String value = texts.item(0).getTextContent().trim();
                if (!value.matches("\"[0-9]{4}\"")) throw new IllegalStateException("Codice diagnosi DMN non supportato.");
                codes.add(value.substring(1, value.length() - 1));
            }
            if (codes.isEmpty()) throw new IllegalStateException("Catalogo diagnosi DMN vuoto.");
            return codes.stream().distinct().sorted().toList();
        } catch (java.io.IOException | org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException exception) {
            throw new IllegalStateException("Impossibile leggere il catalogo diagnosi DMN.", exception);
        }
    }
}
