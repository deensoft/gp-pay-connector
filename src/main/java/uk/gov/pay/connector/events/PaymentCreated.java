package uk.gov.pay.connector.events;

import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.events.eventdetails.PaymentCreatedEventDetails;

import java.time.ZonedDateTime;

public class PaymentCreated extends PaymentEvent {

    public PaymentCreated(String resourceExternalId, PaymentCreatedEventDetails eventDetails, ZonedDateTime timestamp) {
        super(resourceExternalId, eventDetails, timestamp);
    }

    public static PaymentCreated from(ChargeEntity charge) {
        return new PaymentCreated(
                charge.getExternalId(), 
                PaymentCreatedEventDetails.from(charge),
                charge.getCreatedDate());
    }
}