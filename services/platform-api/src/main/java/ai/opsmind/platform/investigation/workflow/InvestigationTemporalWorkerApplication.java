package ai.opsmind.platform.investigation.workflow;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

public final class InvestigationTemporalWorkerApplication {

    private InvestigationTemporalWorkerApplication() {
    }

    public static void main(String[] args) {
        createApplication().run(args);
    }

    static SpringApplication createApplication() {
        SpringApplication application = new SpringApplication(
            InvestigationTemporalWorkerConfiguration.class
        );
        application.setBannerMode(Banner.Mode.OFF);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application;
    }
}
