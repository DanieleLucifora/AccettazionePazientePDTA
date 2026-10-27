package it.uni.pdta.pdta_camunda.workflow;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnExamMappingsTests {
    @Test
    void parallelLaboratoryTasksHaveLocalRequestersAndDirectRoleSpecificReturns() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var input = getClass().getResourceAsStream("/presa_in_carico_pdta.bpmn")) {
            var document = factory.newDocumentBuilder().parse(input);
            var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
            for (var entry : Map.of("Activity_1nqhbxs", "CHIRURGHI", "Activity_diagnostic_oncologo", "ONCOLOGI").entrySet()) {
                var task = (Element) xpath.evaluate("//*[@id='" + entry.getKey() + "']", document, javax.xml.xpath.XPathConstants.NODE);
                var inputs = task.getElementsByTagNameNS("http://camunda.org/schema/zeebe/1.0", "input");
                assertThat(inputs.getLength()).isEqualTo(1);
                assertThat(((Element) inputs.item(0)).getAttribute("target")).isEqualTo("diagnosticRequestRole");
                assertThat(((Element) inputs.item(0)).getAttribute("source")).isEqualTo(entry.getValue());
                String returnFlow = task.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/MODEL", "outgoing").item(0).getTextContent();
                var flow = (Element) xpath.evaluate("//*[@id='" + returnFlow + "']", document, javax.xml.xpath.XPathConstants.NODE);
                assertThat(flow.getAttribute("sourceRef")).isEqualTo(entry.getKey());
                assertThat(flow.getAttribute("targetRef")).isEqualTo(entry.getValue().equals("CHIRURGHI") ? "Activity_14jdrgj" : "Activity_0vww6jw");
                assertThat(flow.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/MODEL", "conditionExpression").getLength()).isZero();
            }
            assertThat(xpath.evaluate("count(//*[@id='Gateway_1mpo32s'])", document)).isEqualTo("0");
        }
    }

    @Test
    void specialistCompletionPropagatesRequesterIdentityAndQueueToLaboratory() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var input = getClass().getResourceAsStream("/presa_in_carico_pdta.bpmn")) {
            assertThat(input).isNotNull();
            var document = factory.newDocumentBuilder().parse(input);
            var tasks = document.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/MODEL", "userTask");
            Map<String, String> expected = Map.of("Activity_14jdrgj", "chirurgo", "Activity_0vww6jw", "oncologo");
            int checked = 0;
            for (int i = 0; i < tasks.getLength(); i++) {
                var task = (Element) tasks.item(i);
                String prefix = expected.get(task.getAttribute("id"));
                if (prefix == null) continue;
                var outputs = task.getElementsByTagNameNS("http://camunda.org/schema/zeebe/1.0", "output");
                Map<String, String> mappings = new HashMap<>();
                for (int j = 0; j < outputs.getLength(); j++) {
                    var output = (Element) outputs.item(j);
                    mappings.put(output.getAttribute("target"), output.getAttribute("source"));
                }
                assertThat(mappings).containsEntry(prefix + "RequesterId", "=requesterId")
                        .containsEntry(prefix + "RequesterName", "=requesterName")
                        .containsEntry("examQueue", "=examQueue")
                        .containsEntry(prefix + "SelectedSlotStart", "=specialistSelectedSlotStart")
                        .containsEntry(prefix + "SelectedProtocolId", "=specialistSelectedProtocolId");
                checked++;
            }
            assertThat(checked).isEqualTo(2);
        }
    }
}
