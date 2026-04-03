package it.uni.pdta.pdta_camunda.cli;

/**
 * Endpoint Camunda usati dalle CLI.
 *
 * Ordine di risoluzione per ogni valore:
 * 1) Java system property (-D...)
 * 2) Environment variable
 * 3) Default locale
 */
public final class CliEndpoints {

    private static final String DEFAULT_GRPC_ADDRESS = "http://127.0.0.1:26500";
    private static final String DEFAULT_REST_ADDRESS = "http://localhost:8080";

    private CliEndpoints() {
        // utility class
    }

    public static String grpcAddress() {
        return read("pdta.camunda.grpc-address", "CAMUNDA_GRPC_ADDRESS", DEFAULT_GRPC_ADDRESS);
    }

    public static String restAddress() {
        return removeTrailingSlash(read("pdta.camunda.rest-address", "CAMUNDA_REST_ADDRESS", DEFAULT_REST_ADDRESS));
    }

    public static String restV2BaseUrl() {
        String explicit = read("pdta.camunda.rest-v2-base-url", "CAMUNDA_REST_V2_BASE_URL", "");
        if (!explicit.isBlank()) {
            return removeTrailingSlash(explicit);
        }
        return restAddress() + "/v2";
    }

    public static String operateUrl() {
        return restAddress() + "/operate";
    }

    private static String read(String systemProperty, String envVar, String fallback) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(envVar);
        }
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String removeTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
