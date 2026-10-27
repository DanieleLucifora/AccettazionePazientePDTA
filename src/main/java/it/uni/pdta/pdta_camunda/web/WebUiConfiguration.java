package it.uni.pdta.pdta_camunda.web;

import io.camunda.client.CamundaClient;
import it.uni.pdta.pdta_camunda.registration.PatientRegistrationService;
import it.uni.pdta.pdta_camunda.workflow.CamundaRestClient;
import it.uni.pdta.pdta_camunda.workflow.SpecialistTaskService;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticExamService;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticTaskService;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticReportService;
import it.uni.pdta.pdta_camunda.scheduling.service.DiagnosticBookingStore;
import it.uni.pdta.pdta_camunda.scheduling.service.BookingService;
import it.uni.pdta.pdta_camunda.scheduling.service.ResourceEventService;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.ExamProtocolRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

@Configuration
@Profile("web-ui")
public class WebUiConfiguration {
    @Bean
    DiagnosticReportService diagnosticReportService(CamundaRestClient rest, DiagnosticBookingStore store) {
        return new DiagnosticReportService(rest, store);
    }

    @Bean
    CamundaRestClient workflowRestClient(RestTemplateBuilder builder,
            @Value("${camunda.client.rest-address}") String restAddress) {
        return new CamundaRestClient(restAddress.replaceAll("/+$", "") + "/v2",
                builder.connectTimeout(Duration.ofSeconds(3)).readTimeout(Duration.ofSeconds(10)).build());
    }

    @Bean
    PatientRegistrationService patientRegistrationService(CamundaClient client, CamundaRestClient restClient) {
        return new PatientRegistrationService(client, restClient);
    }

    @Bean
    SpecialistTaskService specialistTaskService(CamundaRestClient restClient) {
        return new SpecialistTaskService(restClient);
    }

    @Bean
    DiagnosticExamService diagnosticExamService(SpecialistTaskService tasks, CamundaRestClient rest,
            BookingService booking, ExamProtocolRepository protocols, ResourceEventService events) {
        return new DiagnosticExamService(tasks, rest, booking, protocols, events);
    }

    @Bean
    DiagnosticTaskService diagnosticTaskService(CamundaRestClient rest, DiagnosticExamService exams,
            DiagnosticBookingStore store) {
        return new DiagnosticTaskService(rest, exams, store);
    }
}
