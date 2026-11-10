package app.brewflow;

import app.brewflow.bootstrap.BootstrapAdminCommand;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

// no generated in-memory user: accounts come only from bootstrap-admin or an admin session (section 2.1)
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@ConfigurationPropertiesScan
public class BrewFlowApplication {

    public static void main(String[] args) {
        if (args.length > 0 && BootstrapAdminCommand.ARGUMENT.equals(args[0])) {
            System.exit(runBootstrap(Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(BrewFlowApplication.class, args);
    }

    /** One-shot command: creates the first admin and exits (section 2.1). No web server is started. */
    private static int runBootstrap(String[] args) {
        SpringApplication app = new SpringApplication(BrewFlowApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ctx = app.run(args)) {
            return ctx.getBean(BootstrapAdminCommand.class).run(System.getenv(), System.err);
        }
    }
}
