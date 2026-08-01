package ai.opsmind.platform.investigation.workflow;

import ai.opsmind.temporalworker.InvestigationTemporalWorkerBootstrapConfiguration;

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
            InvestigationTemporalWorkerBootstrapConfiguration.class
        );
        application.setBannerMode(Banner.Mode.OFF);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application;
    }
}
