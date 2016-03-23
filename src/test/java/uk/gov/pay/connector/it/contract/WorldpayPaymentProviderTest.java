package uk.gov.pay.connector.it.contract;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import uk.gov.pay.connector.app.GatewayCredentialsConfig;
import uk.gov.pay.connector.model.*;
import uk.gov.pay.connector.model.domain.Card;
import uk.gov.pay.connector.model.domain.ChargeEntity;
import uk.gov.pay.connector.model.domain.GatewayAccountEntity;
import uk.gov.pay.connector.service.worldpay.WorldpayPaymentProvider;

import javax.ws.rs.client.ClientBuilder;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.io.Resources.getResource;
import static java.util.UUID.randomUUID;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static uk.gov.pay.connector.fixture.ChargeEntityFixture.aValidChargeEntity;
import static uk.gov.pay.connector.model.domain.ChargeStatus.AUTHORISATION_SUCCESS;
import static uk.gov.pay.connector.service.GatewayClient.createGatewayClient;
import static uk.gov.pay.connector.util.CardUtils.aValidCard;
import static uk.gov.pay.connector.util.SystemUtils.envOrThrow;

public class WorldpayPaymentProviderTest {

    private GatewayAccountEntity validGatewayAccount;
    private Map<String, String> validCredentails;

    @Before
    public void checkThatWorldpayIsUp() {
        try {
            new URL(getWorldpayConfig().getUrl()).openConnection().connect();

            validCredentails = ImmutableMap.of(
                    "merchant_id", "MERCHANTCODE",
                    "username", envOrThrow("GDS_CONNECTOR_WORLDPAY_USER"),
                    "password", envOrThrow("GDS_CONNECTOR_WORLDPAY_PASSWORD"));

            validGatewayAccount = new GatewayAccountEntity();
            validGatewayAccount.setId(1234L);
            validGatewayAccount.setGatewayName("worldpay");
            validGatewayAccount.setCredentials(validCredentails);

        } catch (IOException ex) {
            Assume.assumeTrue(false);
        }
    }

    @Test
    public void shouldBeAbleToSendAuthorisationRequestForMerchant() throws Exception {
        WorldpayPaymentProvider connector = getValidWorldpayPaymentProvider();
        successfulWorldpayCardAuth(connector);
    }

    /**
     * Worldpay does not care about a successful authorization reference to make a capture request.
     * It simply accepts anything as long as the request is well formed. (And ignores it silently)
     */
    @Test
    public void shouldBeAbleToSendCaptureRequestForMerchant() throws Exception {
        WorldpayPaymentProvider connector = getValidWorldpayPaymentProvider();

        ChargeEntity charge = aValidChargeEntity()
                .withTransactionId(randomUUID().toString())
                .withGatewayAccountEntity(validGatewayAccount)
                .build();

        CaptureResponse response = connector.capture(CaptureRequest.valueOf(charge));

        assertTrue(response.isSuccessful());
    }

    @Test
    public void shouldBeAbleToSendCancelRequestForMerchant() throws Exception {
        WorldpayPaymentProvider connector = getValidWorldpayPaymentProvider();
        AuthorisationResponse response = successfulWorldpayCardAuth(connector);

        ChargeEntity charge = aValidChargeEntity()
                .withTransactionId(response.getTransactionId())
                .withGatewayAccountEntity(validGatewayAccount)
                .build();

        CancelRequest cancelRequest = CancelRequest.valueOf(charge);
        CancelResponse cancelResponse = connector.cancel(cancelRequest);

        assertTrue(cancelResponse.isSuccessful());
        assertNull(cancelResponse.getError());
    }

    @Test
    public void handleNotification_shouldOnlyUpdateChargeStatusOnce() throws Exception {
        WorldpayPaymentProvider connector = getValidWorldpayPaymentProvider();
        AuthorisationResponse response = successfulWorldpayCardAuth(connector);

        Consumer<StatusUpdates> mockAccountUpdater = mock(Consumer.class);

        String transactionId = response.getTransactionId();
        StatusUpdates statusResponse = connector.handleNotification(
                notificationPayloadForTransaction(transactionId, "AUTHORISED"),
                payloadChecks -> true,
                accoundFinder -> Optional.of(validGatewayAccount),
                mockAccountUpdater
        );

        assertThat(statusResponse.getStatusUpdates(), hasItem(Pair.of(transactionId, AUTHORISATION_SUCCESS)));
    }

    @Test
    public void shouldFailRequestAuthorisationIfCredentialsAreNotCorrect() throws Exception {
        String worldpayUrl = getWorldpayConfig().getUrl();

        WorldpayPaymentProvider connector = new WorldpayPaymentProvider(
                createGatewayClient(ClientBuilder.newClient(), worldpayUrl)
        );

        Long gatewayAccountId = 112233L;
        String providerName = "worldpay";
        ImmutableMap<String, String> credentials = ImmutableMap.of(
                "merchant_id", "non-existent-id",
                "username", "non-existent-username",
                "password", "non-existent-password"
        );

        GatewayAccountEntity gatewayAccountEntity = new GatewayAccountEntity(providerName, credentials);
        gatewayAccountEntity.setId(gatewayAccountId);

        ChargeEntity charge = aValidChargeEntity()
                .withGatewayAccountEntity(gatewayAccountEntity)
                .build();

        AuthorisationRequest request = new AuthorisationRequest(charge, aValidCard());
        AuthorisationResponse response = connector.authorise(request);

        assertFalse(response.isSuccessful());
    }

    private AuthorisationRequest getCardAuthorisationRequest() {
        Card card = aValidCard();
        ChargeEntity charge = aValidChargeEntity()
                .withGatewayAccountEntity(validGatewayAccount)
                .build();
        return new AuthorisationRequest(charge, card);
    }

    private AuthorisationResponse successfulWorldpayCardAuth(WorldpayPaymentProvider connector) {
        AuthorisationRequest request = getCardAuthorisationRequest();
        AuthorisationResponse response = connector.authorise(request);

        assertTrue(response.isSuccessful());

        return response;
    }

    private WorldpayPaymentProvider getValidWorldpayPaymentProvider() {
        GatewayCredentialsConfig config = getWorldpayConfig();
        return new WorldpayPaymentProvider(
                createGatewayClient(
                        ClientBuilder.newClient(),
                        config.getUrl()
                )
        );
    }

    private GatewayCredentialsConfig getWorldpayConfig() {
        return WORLDPAY_CREDENTIALS;
    }

    private static final GatewayCredentialsConfig WORLDPAY_CREDENTIALS = new GatewayCredentialsConfig() {
        @Override
        public String getUrl() {
            return "https://secure-test.worldpay.com/jsp/merchant/xml/paymentService.jsp";
        }
    };

    private String notificationPayloadForTransaction(String transactionId, String status) throws IOException {
        URL resource = getResource("templates/worldpay/notification.xml");
        return Resources.toString(resource, Charset.defaultCharset())
                .replace("{{transactionId}}", transactionId)
                .replace("{{status}}", status);
    }
}
