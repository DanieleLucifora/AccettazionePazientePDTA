package it.uni.pdta.pdta_camunda.cli;

import it.uni.pdta.pdta_camunda.PdtaCamundaApplication;
import it.uni.pdta.pdta_camunda.scheduling.service.BookingService;
import it.uni.pdta.pdta_camunda.scheduling.service.ResourceEventService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Bootstrap leggero del context Spring per usare i servizi di scheduling nelle CLI.
 */
public final class SchedulingCliSupport {

    private static ConfigurableApplicationContext context;

    private SchedulingCliSupport() {
    }

    public static synchronized BookingService getBookingService() {
        if (context == null) {
            context = new SpringApplicationBuilder(PdtaCamundaApplication.class)
                    .profiles("cli")
                    .web(WebApplicationType.NONE)
                    .properties(
                            "spring.main.banner-mode=off",
                            "spring.main.log-startup-info=false"
                    )
                    .run();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (context != null && context.isActive()) {
                    context.close();
                }
            }));
        }
        return context.getBean(BookingService.class);
    }

    public static synchronized ResourceEventService getResourceEventService() {
        if (context == null) {
            getBookingService();
        }
        return context.getBean(ResourceEventService.class);
    }
}
