package uk.gov.pay.connector.events.model.payout;

import uk.gov.pay.connector.events.eventdetails.payout.PayoutCreatedEventDetails;
import uk.gov.pay.connector.gateway.stripe.json.StripePayout;

import java.time.ZonedDateTime;

public class PayoutCreated extends PayoutEvent {

    private PayoutCreated(String resourceExternalId, PayoutCreatedEventDetails eventDetails, ZonedDateTime timestamp) {
        super(resourceExternalId, eventDetails, timestamp);
    }

    public static PayoutCreated from(StripePayout payout) {
        return new PayoutCreated(
                payout.getId(),
                new PayoutCreatedEventDetails(
                        payout.getAmount(),
                        payout.getArrivalDate(),
                        payout.getStatus(),
                        payout.getType(),
                        payout.getStatementDescriptor()),
                payout.getCreated());
    }
}
