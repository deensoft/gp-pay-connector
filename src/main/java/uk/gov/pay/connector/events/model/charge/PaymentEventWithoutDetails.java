package uk.gov.pay.connector.events.model.charge;

import uk.gov.pay.connector.events.eventdetails.EmptyEventDetails;

import java.time.Instant;

public class PaymentEventWithoutDetails extends PaymentEvent {

    public PaymentEventWithoutDetails(String serviceId, boolean live, String resourceExternalId, Instant timestamp) {
        super(serviceId, live, resourceExternalId, new EmptyEventDetails(), timestamp);
        
    }
}
