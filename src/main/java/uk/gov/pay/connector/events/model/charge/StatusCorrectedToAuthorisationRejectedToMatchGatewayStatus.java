package uk.gov.pay.connector.events.model.charge;

import java.time.Instant;

public class StatusCorrectedToAuthorisationRejectedToMatchGatewayStatus extends PaymentEventWithoutDetails {
    public StatusCorrectedToAuthorisationRejectedToMatchGatewayStatus(String serviceId, boolean live, Long gatewayAccountInternalId, String resourceExternalId, Instant timestamp) {
        super(serviceId, live, gatewayAccountInternalId, resourceExternalId, timestamp);
    }
}
