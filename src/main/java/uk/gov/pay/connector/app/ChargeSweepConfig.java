package uk.gov.pay.connector.app;

import io.dropwizard.Configuration;

import java.time.Duration;

public class ChargeSweepConfig extends Configuration {

    private int defaultChargeExpiryThreshold;
    private int awaitingCaptureExpiryThreshold;
    private int tokenExpiryThresholdInSeconds;

    public Duration getDefaultChargeExpiryThreshold() {
        return Duration.ofSeconds(defaultChargeExpiryThreshold);
    }

    public Duration getAwaitingCaptureExpiryThreshold() {
        return Duration.ofSeconds(awaitingCaptureExpiryThreshold);
    }
    
    public Duration getTokenExpiryThresholdInSeconds() {
        return Duration.ofSeconds(tokenExpiryThresholdInSeconds);
    }
}
