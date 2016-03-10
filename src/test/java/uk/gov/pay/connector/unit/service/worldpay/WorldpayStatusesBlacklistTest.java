package uk.gov.pay.connector.unit.service.worldpay;

import org.junit.Test;
import uk.gov.pay.connector.service.ChargeStatusBlacklist;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static uk.gov.pay.connector.model.domain.ChargeStatus.*;

public class WorldpayStatusesBlacklistTest {

    ChargeStatusBlacklist chargeStatusBlacklist = new ChargeStatusBlacklist();

    @Test
    public void shouldBlacklistedAuthorisedStatuses() throws Exception {
        assertTrue(chargeStatusBlacklist.has(AUTHORISATION_SUBMITTED));
        assertTrue(chargeStatusBlacklist.has(AUTHORISATION_READY));
        assertTrue(chargeStatusBlacklist.has(AUTHORISATION_SUCCESS));
        assertTrue(chargeStatusBlacklist.has(AUTHORISATION_REJECTED));
    }

    @Test
    public void shouldNotBlacklistNonAuthorisedStatuses() throws Exception {
        assertFalse(chargeStatusBlacklist.has(CAPTURED));
        assertFalse(chargeStatusBlacklist.has(SYSTEM_CANCELLED));
    }
}