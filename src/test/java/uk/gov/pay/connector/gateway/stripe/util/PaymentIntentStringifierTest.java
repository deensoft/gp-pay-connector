package uk.gov.pay.connector.gateway.stripe.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uk.gov.pay.connector.gateway.stripe.json.StripePaymentIntent;
import uk.gov.pay.connector.gateway.stripe.response.StripeNotification;
import uk.gov.pay.connector.util.TestTemplateResourceLoader;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static uk.gov.pay.connector.gateway.stripe.StripeNotificationType.PAYMENT_INTENT_AMOUNT_CAPTURABLE_UPDATED;
import static uk.gov.pay.connector.gateway.stripe.StripeNotificationType.PAYMENT_INTENT_PAYMENT_FAILED;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.STRIPE_NOTIFICATION_PAYMENT_INTENT;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.STRIPE_NOTIFICATION_PAYMENT_INTENT_PAYMENT_FAILED;

class PaymentIntentStringifierTest {
    private ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldBuildStringFromPaymentIntentFailed() throws JsonProcessingException {
        String payload = TestTemplateResourceLoader.load(STRIPE_NOTIFICATION_PAYMENT_INTENT_PAYMENT_FAILED)
                .replace("{{id}}", "pi_123")
                .replace("{{type}}", PAYMENT_INTENT_PAYMENT_FAILED.getType());
        StripeNotification notification = mapper.readValue(payload, StripeNotification.class);
        StripePaymentIntent paymentIntent = mapper.readValue(notification.getObject(), StripePaymentIntent.class);

        String stringified = PaymentIntentStringifier.stringify(paymentIntent);

        assertThat(stringified, containsString("stripe charge: ch_3K6dQPHj08j2jFuB1f9K4dcde"));
        assertThat(stringified, containsString("type: invalid_request_error"));
        assertThat(stringified, containsString("code: card_declined"));
        assertThat(stringified, containsString("message: Your card was declined"));
        assertThat(stringified, containsString("decline code: generic_decline"));
        assertThat(stringified, containsString("payment intent: pi_123"));
        assertThat(stringified, containsString("outcome.network_status: declined_by_network"));
        assertThat(stringified, containsString("outcome.reason: insufficient_funds"));
        assertThat(stringified, containsString("outcome.risk_level: normal"));
        assertThat(stringified, containsString("outcome.seller_message: The bank returned the decline code `insufficient_funds`."));
        assertThat(stringified, containsString("outcome.type: issuer_declined"));
    }

    @Test
    void shouldBuildStringFromPaymentIntentSuccess() throws JsonProcessingException {
        String payload = TestTemplateResourceLoader.load(STRIPE_NOTIFICATION_PAYMENT_INTENT)
                .replace("{{id}}", "pi_123")
                .replace("{{type}}", PAYMENT_INTENT_AMOUNT_CAPTURABLE_UPDATED.getType());
        StripeNotification notification = mapper.readValue(payload, StripeNotification.class);
        StripePaymentIntent paymentIntent = mapper.readValue(notification.getObject(), StripePaymentIntent.class);

        String stringified = PaymentIntentStringifier.stringify(paymentIntent);

        assertThat(stringified, containsString("stripe charge: ch_1FF3RuEZsufgnuO0IPT8CY3o"));
    }
}