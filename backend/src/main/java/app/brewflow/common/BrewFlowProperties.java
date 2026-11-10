package app.brewflow.common;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("brewflow")
public record BrewFlowProperties(
        String appMode,
        String fiscalSopApproved,
        String fiscalSopId,
        String deploymentTier,
        String currency,
        ZoneId timezone,
        Duration quoteTtl,
        Login login,
        Kds kds) {

    public record Login(int maxFailedAttempts, int ipMaxFailures, Duration ipWindow) {
    }

    public record Kds(Duration heartbeat) {
    }
}
