package app.brewflow.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration
public class TimeConfig {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    /** Timestamps are UTC at microsecond precision, matching timestamptz. Business dates use the site timezone. */
    @Component
    public static class TimeSource {
        private final Clock clock;
        private final BrewFlowProperties props;

        public TimeSource(Clock clock, BrewFlowProperties props) {
            this.clock = clock;
            this.props = props;
        }

        public OffsetDateTime now() {
            return OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        }

        public LocalDate today() {
            return LocalDate.ofInstant(clock.instant(), props.timezone());
        }
    }
}
