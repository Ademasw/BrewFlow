package app.brewflow;

import app.brewflow.common.AppMode;
import app.brewflow.common.BrewFlowProperties;
import app.brewflow.order.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Section 3 and T016: startup fails closed; APP_MODE is independent of deployment_tier. */
class AppModeTest {

    @Configuration
    @EnableConfigurationProperties(BrewFlowProperties.class)
    @Import(AppMode.class)
    static class ModeOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ModeOnly.class)
            .withPropertyValues("brewflow.deployment-tier=local");

    @Test
    void missingOrMisspelledModeRefusesToStart() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("brewflow.app-mode=").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("brewflow.app-mode=training").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("brewflow.app-mode=DEMO").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void liveNeedsTheSopGate() {
        runner.withPropertyValues("brewflow.app-mode=LIVE").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("brewflow.app-mode=LIVE", "brewflow.fiscal-sop-approved=TRUE",
                "brewflow.fiscal-sop-id=SOP-1").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("brewflow.app-mode=LIVE", "brewflow.fiscal-sop-approved=true")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("brewflow.app-mode=LIVE", "brewflow.fiscal-sop-approved=false",
                "brewflow.fiscal-sop-id=SOP-1").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("brewflow.app-mode=LIVE", "brewflow.fiscal-sop-approved=true",
                "brewflow.fiscal-sop-id=SOP-1").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            AppMode mode = ctx.getBean(AppMode.class);
            assertThat(mode.allows(PaymentMethod.TRAINING)).isFalse();
            assertThat(mode.allows(PaymentMethod.CASH)).isTrue();
            assertThat(mode.allows(PaymentMethod.EXTERNAL_CARD)).isTrue();
            assertThat(mode.allows(PaymentMethod.EXTERNAL_QR)).isTrue();
        });
    }

    @Test
    void productionTierMayRunTrainingAndStillRejectsLiveMethods() {
        runner.withPropertyValues("brewflow.app-mode=TRAINING", "brewflow.deployment-tier=production").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            AppMode mode = ctx.getBean(AppMode.class);
            assertThat(mode.isLive()).isFalse();
            assertThat(mode.allows(PaymentMethod.TRAINING)).isTrue();
            assertThat(mode.allows(PaymentMethod.CASH)).isFalse();
            assertThat(mode.allows(PaymentMethod.EXTERNAL_CARD)).isFalse();
            assertThat(mode.allows(PaymentMethod.EXTERNAL_QR)).isFalse();
        });
        runner.withPropertyValues("brewflow.app-mode=TRAINING", "brewflow.deployment-tier=prod")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void theRealApplicationRefusesToBootWithoutAppMode() {
        SpringApplication app = new SpringApplication(BrewFlowApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> app.run("--brewflow.app-mode=",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/brewflow_test"))
                .hasStackTraceContaining("APP_MODE must be exactly TRAINING or LIVE");
    }

    /** bootstrap-admin starts the application without a web server; that context must start. */
    @Test
    void theCommandModeContextStarts() {
        SpringApplication app = new SpringApplication(BrewFlowApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var ctx = app.run("--brewflow.app-mode=TRAINING",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/brewflow_test")) {
            assertThat(ctx.getBean(app.brewflow.bootstrap.BootstrapAdminCommand.class)).isNotNull();
        }
    }

    @Test
    void theRealApplicationRefusesLiveWithoutSop() {
        SpringApplication app = new SpringApplication(BrewFlowApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> app.run("--brewflow.app-mode=LIVE", "--brewflow.fiscal-sop-approved=false",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/brewflow_test"))
                .hasStackTraceContaining("FISCAL_SOP_APPROVED=true");
    }
}
