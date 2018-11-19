package uk.gov.pay.connector.it.contract;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import io.dropwizard.setup.Environment;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import uk.gov.pay.connector.app.ConnectorConfiguration;
import uk.gov.pay.connector.app.GatewayConfig;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.gateway.GatewayClient;
import uk.gov.pay.connector.gateway.GatewayClientFactory;
import uk.gov.pay.connector.gateway.PaymentGatewayName;
import uk.gov.pay.connector.gateway.PaymentProvider;
import uk.gov.pay.connector.gateway.model.AuthCardDetails;
import uk.gov.pay.connector.gateway.model.request.CardAuthorisationGatewayRequest;
import uk.gov.pay.connector.gateway.model.request.CancelGatewayRequest;
import uk.gov.pay.connector.gateway.model.request.CaptureGatewayRequest;
import uk.gov.pay.connector.gateway.model.request.RefundGatewayRequest;
import uk.gov.pay.connector.gateway.model.response.GatewayResponse;
import uk.gov.pay.connector.gateway.smartpay.SmartpayAuthorisationResponse;
import uk.gov.pay.connector.gateway.smartpay.SmartpayCaptureHandler;
import uk.gov.pay.connector.gateway.smartpay.SmartpayCaptureResponse;
import uk.gov.pay.connector.gateway.smartpay.SmartpayPaymentProvider;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.model.domain.AuthCardDetailsFixture;
import uk.gov.pay.connector.refund.model.domain.RefundEntity;
import uk.gov.pay.connector.util.TestClientFactory;

import javax.ws.rs.client.Client;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiFunction;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity.Type.TEST;
import static uk.gov.pay.connector.model.domain.ChargeEntityFixture.aValidChargeEntity;
import static uk.gov.pay.connector.model.domain.RefundEntityFixture.userExternalId;
import static uk.gov.pay.connector.util.SystemUtils.envOrThrow;

@Ignore("Ignoring as this test is failing in Jenkins because it's failing to locate the certificates - PP-1707")
@RunWith(MockitoJUnitRunner.class)
public class SmartpayPaymentProviderTest {
    private String url = "https://pal-test.barclaycardsmartpay.com/pal/servlet/soap/Payment";
    private String username;
    private String password;
    private ChargeEntity chargeEntity;
    private GatewayAccountEntity gatewayAccountEntity;
    private MetricRegistry mockMetricRegistry;
    private Environment mockEnvironment;

    private static final String VALID_SMARTPAY_3DS_CARD_NUMBER = "5212345678901234";
    private static final String VALID_SMARTPAY_CARD_NUMBER = "5555444433331111";

    @Before
    public void setUpAndCheckThatSmartpayIsUp() throws IOException {
        try {
            username = envOrThrow("GDS_CONNECTOR_SMARTPAY_USER");
            password = envOrThrow("GDS_CONNECTOR_SMARTPAY_PASSWORD");
        } catch (IllegalStateException ex) {
            Assume.assumeTrue("Ignoring test since credentials not configured", false);
        }

        new URL(url).openConnection().connect();
        Map<String, String> validSmartPayCredentials = ImmutableMap.of(
                "merchant_id", "DCOTest",
                "username", username,
                "password", password);
        gatewayAccountEntity = new GatewayAccountEntity();
        gatewayAccountEntity.setId(123L);
        gatewayAccountEntity.setGatewayName("smartpay");
        gatewayAccountEntity.setCredentials(validSmartPayCredentials);
        gatewayAccountEntity.setType(TEST);

        chargeEntity = aValidChargeEntity()
                .withGatewayAccountEntity(gatewayAccountEntity).build();

        mockMetricRegistry = mock(MetricRegistry.class);
        Histogram mockHistogram = mock(Histogram.class);
        Counter mockCounter = mock(Counter.class);
        when(mockMetricRegistry.histogram(anyString())).thenReturn(mockHistogram);
        when(mockMetricRegistry.counter(anyString())).thenReturn(mockCounter);
        mockEnvironment = mock(Environment.class);
        when(mockEnvironment.metrics()).thenReturn(mockMetricRegistry);
    }

    @Test
    public void shouldSendSuccessfullyAnOrderForMerchant() {
        PaymentProvider paymentProvider = getSmartpayPaymentProvider();
        CardAuthorisationGatewayRequest request = getCardAuthorisationRequest(chargeEntity);
        GatewayResponse<SmartpayAuthorisationResponse> response = paymentProvider.authorise(request);
        assertTrue(response.isSuccessful());
    }

    @Test
    public void shouldSendSuccessfullyAnOrderForMerchantWithNoAddressInRequest() {
        PaymentProvider paymentProvider = getSmartpayPaymentProvider();
        AuthCardDetails authCardDetails = AuthCardDetailsFixture.anAuthCardDetails()
                .withCardNo(VALID_SMARTPAY_CARD_NUMBER)
                .withAddress(null)
                .build();

        CardAuthorisationGatewayRequest request = new CardAuthorisationGatewayRequest(chargeEntity, authCardDetails);

        GatewayResponse response = paymentProvider.authorise(request);
        assertTrue(response.isSuccessful());
    }

    @Test
    public void shouldSendA3dsOrderForMerchantSuccessfully() {
        gatewayAccountEntity.setRequires3ds(true);
        PaymentProvider paymentProvider = getSmartpayPaymentProvider();
        CardAuthorisationGatewayRequest request = getCard3dsAuthorisationRequest(chargeEntity);
        GatewayResponse<SmartpayAuthorisationResponse> response = paymentProvider.authorise(request);
        assertTrue(response.isSuccessful());
        assertThat(response.getBaseResponse().get().getIssuerUrl(), is(notNullValue()));
        assertThat(response.getBaseResponse().get().getMd(), is(notNullValue()));
        assertThat(response.getBaseResponse().get().getPaRequest(), is(notNullValue()));
    }

    @Test
    public void shouldSendA3dsOrderForMerchantWithNoBillingAddressSuccessfully() {
        gatewayAccountEntity.setRequires3ds(true);
        PaymentProvider paymentProvider = getSmartpayPaymentProvider();

        AuthCardDetails authCardDetails = AuthCardDetailsFixture.anAuthCardDetails()
                .withCardNo(VALID_SMARTPAY_3DS_CARD_NUMBER)
                .withAddress(null)
                .build();

        CardAuthorisationGatewayRequest request = new CardAuthorisationGatewayRequest(chargeEntity, authCardDetails);
        GatewayResponse<SmartpayAuthorisationResponse> response = paymentProvider.authorise(request);
        assertTrue(response.isSuccessful());
        assertThat(response.getBaseResponse().get().getIssuerUrl(), is(notNullValue()));
        assertThat(response.getBaseResponse().get().getMd(), is(notNullValue()));
        assertThat(response.getBaseResponse().get().getPaRequest(), is(notNullValue()));
    }

    @Test
    public void shouldFailRequestAuthorisationIfCredentialsAreNotCorrect() {
        PaymentProvider paymentProvider = getSmartpayPaymentProvider();
        GatewayAccountEntity accountWithInvalidCredentials = new GatewayAccountEntity();
        accountWithInvalidCredentials.setId(11L);
        accountWithInvalidCredentials.setGatewayName("smartpay");
        accountWithInvalidCredentials.setCredentials(ImmutableMap.of(
                "merchant_id", "MerchantAccount",
                "username", "wrong-username",
                "password", "wrong-password"
        ));
        accountWithInvalidCredentials.setType(TEST);

        chargeEntity.setGatewayAccount(accountWithInvalidCredentials);
        CardAuthorisationGatewayRequest request = getCardAuthorisationRequest(chargeEntity);
        GatewayResponse<SmartpayAuthorisationResponse> response = paymentProvider.authorise(request);

        assertFalse(response.isSuccessful());
        assertNotNull(response.getGatewayError());
    }

    @Test
    public void shouldSuccessfullySendACaptureRequest() {
        PaymentProvider paymentProvider = getSmartpayPaymentProvider();
        SmartpayCaptureHandler smartpayCaptureHandler = (SmartpayCaptureHandler) paymentProvider.getCaptureHandler();

        CardAuthorisationGatewayRequest request = getCardAuthorisationRequest(chargeEntity);
        GatewayResponse<SmartpayAuthorisationResponse> response = paymentProvider.authorise(request);

        assertTrue(response.isSuccessful());
        assertThat(response.getBaseResponse().isPresent(), is(true));
        String transactionId = response.getBaseResponse().get().getPspReference();
        assertThat(transactionId, is(not(nullValue())));

        chargeEntity.setGatewayTransactionId(transactionId);

        GatewayResponse<SmartpayCaptureResponse> captureGatewayResponse = smartpayCaptureHandler.capture(CaptureGatewayRequest.valueOf(chargeEntity));
        assertTrue(captureGatewayResponse.isSuccessful());
    }

    @Test
    public void shouldSuccessfullySendACancelRequest() {
        PaymentProvider paymentProvider = getSmartpayPaymentProvider();
        CardAuthorisationGatewayRequest request = getCardAuthorisationRequest(chargeEntity);
        GatewayResponse<SmartpayAuthorisationResponse> response = paymentProvider.authorise(request);
        assertTrue(response.isSuccessful());

        assertThat(response.getBaseResponse().isPresent(), is(true));
        String transactionId = response.getBaseResponse().get().getPspReference();
        assertThat(transactionId, is(not(nullValue())));

        chargeEntity.setGatewayTransactionId(transactionId);

        GatewayResponse cancelResponse = paymentProvider.cancel(CancelGatewayRequest.valueOf(chargeEntity));
        assertThat(cancelResponse.isSuccessful(), is(true));

    }

    @Test
    public void shouldRefundToAnExistingPaymentSuccessfully() {
        CardAuthorisationGatewayRequest request = getCardAuthorisationRequest(chargeEntity);
        PaymentProvider smartpay = getSmartpayPaymentProvider();
        SmartpayCaptureHandler smartpayCaptureHandler = (SmartpayCaptureHandler) smartpay.getCaptureHandler();
        GatewayResponse<SmartpayAuthorisationResponse> authoriseResponse = smartpay.authorise(request);
        assertTrue(authoriseResponse.isSuccessful());

        chargeEntity.setGatewayTransactionId(authoriseResponse.getBaseResponse().get().getPspReference());

        GatewayResponse<SmartpayCaptureResponse> captureGatewayResponse = smartpayCaptureHandler.capture(CaptureGatewayRequest.valueOf(chargeEntity));
        assertTrue(captureGatewayResponse.isSuccessful());

        RefundEntity refundEntity = new RefundEntity(chargeEntity, 1L, userExternalId);
        RefundGatewayRequest refundRequest = RefundGatewayRequest.valueOf(refundEntity);
        GatewayResponse refundResponse = smartpay.refund(refundRequest);

        assertThat(refundResponse.isSuccessful(), is(true));

    }

    private PaymentProvider getSmartpayPaymentProvider() {
        Client client = TestClientFactory.createJerseyClient();

        GatewayClient gatewayClient = new GatewayClient(client, ImmutableMap.of(TEST.toString(), url),
                SmartpayPaymentProvider.includeSessionIdentifier(), mockMetricRegistry);

        GatewayClientFactory gatewayClientFactory = mock(GatewayClientFactory.class);
        when(gatewayClientFactory.createGatewayClient(any(PaymentGatewayName.class), any(Map.class), any(BiFunction.class), any(MetricRegistry.class))).thenReturn(gatewayClient);

        GatewayConfig gatewayConfig = mock(GatewayConfig.class);
        when(gatewayConfig.getUrls()).thenReturn(Collections.EMPTY_MAP);

        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getGatewayConfigFor(PaymentGatewayName.SMARTPAY)).thenReturn(gatewayConfig);

        return new SmartpayPaymentProvider(configuration, gatewayClientFactory, mockEnvironment, new ObjectMapper());
    }

    public static CardAuthorisationGatewayRequest getCardAuthorisationRequest(ChargeEntity chargeEntity) {
        AuthCardDetails authCardDetails = AuthCardDetailsFixture.anAuthCardDetails()
                .withCardNo(VALID_SMARTPAY_CARD_NUMBER)
                .build();

        return new CardAuthorisationGatewayRequest(chargeEntity, authCardDetails);
    }

    public static CardAuthorisationGatewayRequest getCard3dsAuthorisationRequest(ChargeEntity chargeEntity) {
        AuthCardDetails authCardDetails = AuthCardDetailsFixture.anAuthCardDetails()
                .withCardNo(VALID_SMARTPAY_3DS_CARD_NUMBER)
                .build();

        return new CardAuthorisationGatewayRequest(chargeEntity, authCardDetails);
    }
}
