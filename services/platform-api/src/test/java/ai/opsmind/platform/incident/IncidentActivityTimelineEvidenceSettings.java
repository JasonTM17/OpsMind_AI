package ai.opsmind.platform.incident;

record IncidentActivityTimelineEvidenceSettings(
    String url,
    String migrationUser,
    String migrationPassword,
    String appUser,
    String appPassword
) {
    static IncidentActivityTimelineEvidenceSettings fromEnvironment() {
        return new IncidentActivityTimelineEvidenceSettings(
            required("SPRING_DATASOURCE_URL"),
            required("POSTGRES_USER"),
            required("POSTGRES_PASSWORD"),
            required("POSTGRES_APP_USER"),
            required("POSTGRES_APP_PASSWORD")
        );
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for V009 evidence.");
        }
        return value;
    }
}
