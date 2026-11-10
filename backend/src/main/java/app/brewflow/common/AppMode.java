package app.brewflow.common;

import app.brewflow.order.PaymentMethod;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Section 3: APP_MODE is a server setting, independent of the deployment tier. Startup fails closed.
 */
@Component
public class AppMode {

    public enum Mode { TRAINING, LIVE }

    private static final Logger log = LoggerFactory.getLogger(AppMode.class);
    private static final Set<String> TIERS = Set.of("local", "test", "staging", "production");

    private final Mode mode;
    private final String deploymentTier;

    public AppMode(BrewFlowProperties props) {
        String raw = props.appMode();
        if (!"TRAINING".equals(raw) && !"LIVE".equals(raw)) {
            throw new IllegalStateException("APP_MODE must be exactly TRAINING or LIVE; refusing to start");
        }
        this.mode = Mode.valueOf(raw);
        if (mode == Mode.LIVE) {
            String sopId = props.fiscalSopId();
            if (!"true".equals(props.fiscalSopApproved()) || sopId == null || sopId.isBlank()) {
                throw new IllegalStateException(
                        "APP_MODE=LIVE requires FISCAL_SOP_APPROVED=true and a non-empty FISCAL_SOP_ID; refusing to start");
            }
        }
        if (!TIERS.contains(props.deploymentTier())) {
            throw new IllegalStateException("DEPLOYMENT_TIER must be one of " + TIERS + "; refusing to start");
        }
        this.deploymentTier = props.deploymentTier();
        log.info("BrewFlow starting with APP_MODE={} deployment_tier={}", mode, deploymentTier);
    }

    public Mode mode() {
        return mode;
    }

    public boolean isLive() {
        return mode == Mode.LIVE;
    }

    public String deploymentTier() {
        return deploymentTier;
    }

    /** TRAINING accepts only the TRAINING method; LIVE accepts only live methods. */
    public boolean allows(PaymentMethod method) {
        return isLive() ? method != PaymentMethod.TRAINING : method == PaymentMethod.TRAINING;
    }

    public void requireAllowed(PaymentMethod method) {
        if (!allows(method)) {
            throw new ApiException(ErrorCode.MODE_REJECTED,
                    "Payment method " + method + " is not allowed while APP_MODE=" + mode + ".",
                    Map.of("appMode", mode.name(), "paymentMethod", method.name()));
        }
    }
}
