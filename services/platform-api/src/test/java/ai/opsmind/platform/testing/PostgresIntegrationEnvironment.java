package ai.opsmind.platform.testing;

public record PostgresIntegrationEnvironment(
    String jdbcUrl,
    String adminUser,
    String adminPassword,
    String appUser,
    String appPassword,
    String dispatcherUser,
    String dispatcherPassword
) {
    public static PostgresIntegrationEnvironment fromProcess() {
        return new PostgresIntegrationEnvironment(
            requiredEnvironment("SPRING_DATASOURCE_URL"),
            requiredEnvironment("POSTGRES_USER"),
            requiredEnvironment("POSTGRES_PASSWORD"),
            requiredEnvironment("POSTGRES_APP_USER"),
            requiredEnvironment("POSTGRES_APP_PASSWORD"),
            requiredEnvironment("POSTGRES_DISPATCHER_USER"),
            requiredEnvironment("POSTGRES_DISPATCHER_PASSWORD")
        );
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the PostgreSQL integration contract.");
        }
        return value;
    }
}
