package uk.gov.pay.connector.it.resources.worldpay;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.apache.commons.lang.math.RandomUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import uk.gov.pay.connector.app.ConnectorApp;
import uk.gov.pay.connector.it.base.ChargingITestBase;
import uk.gov.pay.connector.it.dao.DatabaseFixtures;
import uk.gov.pay.connector.junit.DropwizardConfig;
import uk.gov.pay.connector.junit.DropwizardJUnitRunner;
import uk.gov.pay.connector.refund.model.domain.RefundStatus;
import uk.gov.pay.connector.util.AddGatewayAccountCredentialsParams;
import uk.gov.pay.connector.util.TestTemplateResourceLoader;
import uk.gov.service.payments.commons.model.ErrorIdentifier;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToXml;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.lang.String.format;
import static javax.ws.rs.core.Response.Status.ACCEPTED;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.OK;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.CAPTURED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.CAPTURE_SUBMITTED;
import static uk.gov.pay.connector.gateway.PaymentGatewayName.STRIPE;
import static uk.gov.pay.connector.gateway.PaymentGatewayName.WORLDPAY;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccount.CREDENTIALS_MERCHANT_CODE;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccount.CREDENTIALS_PASSWORD;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccount.CREDENTIALS_USERNAME;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccount.ONE_OFF_CUSTOMER_INITIATED;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialState.ACTIVE;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialState.RETIRED;
import static uk.gov.pay.connector.it.dao.DatabaseFixtures.withDatabaseTestHelper;
import static uk.gov.pay.connector.matcher.RefundsMatcher.aRefundMatching;
import static uk.gov.pay.connector.rules.WorldpayMockClient.WORLDPAY_URL;
import static uk.gov.pay.connector.util.AddGatewayAccountCredentialsParams.AddGatewayAccountCredentialsParamsBuilder.anAddGatewayAccountCredentialsParams;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_VALID_REFUND_WORLDPAY_REQUEST;

@RunWith(DropwizardJUnitRunner.class)
@DropwizardConfig(app = ConnectorApp.class, config = "config/test-it-config.yaml")
public class WorldpayRefundsResourceIT extends ChargingITestBase {

    private DatabaseFixtures.TestAccount defaultTestAccount;
    private DatabaseFixtures.TestCharge defaultTestCharge;

    public WorldpayRefundsResourceIT() {
        super("worldpay");
    }

    @Before
    public void setUp() {
        super.setUp();
        defaultTestAccount = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestAccount()
                .withAccountId(Long.parseLong(accountId))
                .withGatewayAccountCredentials(List.of(credentialParams))
                .withCredentials(credentials);

        defaultTestCharge = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestCharge()
                .withAmount(100L)
                .withTransactionId("MyUniqueTransactionId!")
                .withTestAccount(defaultTestAccount)
                .withChargeStatus(CAPTURED)
                .withPaymentProvider(getPaymentProvider())
                .withGatewayCredentialId(credentialParams.getId())
                .insert();
    }

    @Test
    public void shouldBeAbleToRequestARefund_partialAmount() {
        Long refundAmount = 50L;

        worldpayMockClient.mockRefundSuccess();
        ValidatableResponse validatableResponse = postRefundFor(defaultTestCharge.getExternalChargeId(), refundAmount, defaultTestCharge.getAmount());
        String refundId = assertRefundResponseWith(refundAmount, validatableResponse, ACCEPTED.getStatusCode());

        List<Map<String, Object>> refundsFoundByChargeExternalId = databaseTestHelper.getRefundsByChargeExternalId(defaultTestCharge.getExternalChargeId());
        assertThat(refundsFoundByChargeExternalId.size(), is(1));
        assertThat(refundsFoundByChargeExternalId, hasItems(aRefundMatching(refundId, is(notNullValue()), defaultTestCharge.getExternalChargeId(), refundAmount, "REFUND SUBMITTED")));

        List<String> refundsHistory = databaseTestHelper.getRefundsHistoryByChargeExternalId(defaultTestCharge.getExternalChargeId()).stream().map(x -> x.get("status").toString()).collect(Collectors.toList());
        assertThat(refundsHistory.size(), is(2));
        assertThat(refundsHistory, containsInAnyOrder("REFUND SUBMITTED", "CREATED"));
    }

    @Test
    public void shouldBeAbleToRequestARefund_fullAmount() {

        Long refundAmount = defaultTestCharge.getAmount();

        worldpayMockClient.mockRefundSuccess();
        ValidatableResponse validatableResponse = postRefundFor(defaultTestCharge.getExternalChargeId(), refundAmount, defaultTestCharge.getAmount());
        String refundId = assertRefundResponseWith(refundAmount, validatableResponse, ACCEPTED.getStatusCode());

        List<Map<String, Object>> refundsFoundByChargeExternalId = databaseTestHelper.getRefundsByChargeExternalId(defaultTestCharge.getExternalChargeId());
        assertThat(refundsFoundByChargeExternalId.size(), is(1));
        assertThat(refundsFoundByChargeExternalId, hasItems(aRefundMatching(refundId, is(notNullValue()), defaultTestCharge.getExternalChargeId(), refundAmount, "REFUND SUBMITTED")));
        assertThat(refundsFoundByChargeExternalId.get(0), hasEntry("charge_external_id", defaultTestCharge.getExternalChargeId()));
        assertThat(refundsFoundByChargeExternalId.get(0), hasEntry("gateway_transaction_id", refundId));

        String expectedRequestBody = TestTemplateResourceLoader.load(WORLDPAY_VALID_REFUND_WORLDPAY_REQUEST)
                .replace("{{merchantCode}}", "merchant-id")
                .replace("{{transactionId}}", "MyUniqueTransactionId!")
                .replace("{{refundReference}}", refundsFoundByChargeExternalId.get(0).get("external_id").toString())
                .replace("{{amount}}", "100");

        verifyRequestBodyToWorldpay(WORLDPAY_URL, expectedRequestBody);
    }

    public void shouldBeAbleToRequestForRetiredCredentials() {
        long accountId = RandomUtils.nextInt();
        AddGatewayAccountCredentialsParams stripeCredentialsParams = anAddGatewayAccountCredentialsParams()
                .withPaymentProvider(STRIPE.getName())
                .withGatewayAccountId(accountId)
                .withState(ACTIVE)
                .build();
        AddGatewayAccountCredentialsParams worldpayCredentialsParams = anAddGatewayAccountCredentialsParams()
                .withPaymentProvider(WORLDPAY.getName())
                .withGatewayAccountId(accountId)
                .withState(RETIRED)
                .withCredentials(Map.of(
                        ONE_OFF_CUSTOMER_INITIATED, Map.of(
                                CREDENTIALS_MERCHANT_CODE, "a-merchant-code",
                                CREDENTIALS_USERNAME, "a-username",
                                CREDENTIALS_PASSWORD, "a-password")))
                .build();
        DatabaseFixtures.TestAccount testAccount = withDatabaseTestHelper(databaseTestHelper)
                .aTestAccount()
                .withAccountId(accountId)
                .withGatewayAccountCredentials(List.of(stripeCredentialsParams, worldpayCredentialsParams));
        DatabaseFixtures.TestCharge charge = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestCharge()
                .withAmount(100L)
                .withTransactionId("MyUniqueTransactionId2!")
                .withTestAccount(testAccount)
                .withChargeStatus(CAPTURED)
                .withPaymentProvider(WORLDPAY.getName())
                .insert();
        
        Long refundAmount = 100L;

        worldpayMockClient.mockRefundSuccess();
        ValidatableResponse validatableResponse = postRefundFor(charge.getExternalChargeId(), refundAmount, defaultTestCharge.getAmount());
        assertRefundResponseWith(refundAmount, validatableResponse, ACCEPTED.getStatusCode());

        wireMockServer.verify(postRequestedFor(urlPathEqualTo(WORLDPAY_URL)));
    }

    private void verifyRequestBodyToWorldpay(String path, String body) {
        wireMockServer.verify(
                postRequestedFor(urlPathEqualTo(path))
                        .withHeader("Content-Type", equalTo("application/xml"))
                        .withHeader("Authorization", equalTo("Basic dGVzdC11c2VyOnRlc3QtcGFzc3dvcmQ="))
                        .withRequestBody(equalToXml(body)));
    }

    @Test
    public void shouldBeAbleToRequestARefund_multiplePartialAmounts_andRefundShouldBeInFullStatus() {

        Long firstRefundAmount = 80L;
        Long secondRefundAmount = 20L;
        Long chargeId = defaultTestCharge.getChargeId();
        String externalChargeId = defaultTestCharge.getExternalChargeId();

        worldpayMockClient.mockRefundSuccess();
        ValidatableResponse firstValidatableResponse = postRefundFor(externalChargeId, firstRefundAmount, defaultTestCharge.getAmount());
        String firstRefundId = assertRefundResponseWith(firstRefundAmount, firstValidatableResponse, ACCEPTED.getStatusCode());

        worldpayMockClient.mockRefundSuccess();
        ValidatableResponse secondValidatableResponse = postRefundFor(externalChargeId, secondRefundAmount, defaultTestCharge.getAmount() - firstRefundAmount);
        String secondRefundId = assertRefundResponseWith(secondRefundAmount, secondValidatableResponse, ACCEPTED.getStatusCode());

        List<Map<String, Object>> refundsFoundByChargeExternalId = databaseTestHelper.getRefundsByChargeExternalId(externalChargeId);
        assertThat(refundsFoundByChargeExternalId.size(), is(2));

        assertThat(refundsFoundByChargeExternalId, hasItems(
                aRefundMatching(secondRefundId, is(notNullValue()), externalChargeId, secondRefundAmount, "REFUND SUBMITTED"),
                aRefundMatching(firstRefundId, is(notNullValue()), externalChargeId, firstRefundAmount, "REFUND SUBMITTED")));

        connectorRestApiClient.withChargeId(externalChargeId)
                .getCharge()
                .statusCode(200)
                .body("refund_summary.status", is("full"))
                .body("refund_summary.amount_available", is(0))
                .body("refund_summary.amount_submitted", is(100));
    }

    @Test
    public void shouldFailRequestingARefund_whenChargeStatusMakesItNotRefundable() {

        DatabaseFixtures.TestCharge testCharge = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestCharge()
                .withAmount(100L)
                .withTestAccount(defaultTestAccount)
                .withChargeStatus(CAPTURE_SUBMITTED)
                .withPaymentProvider(getPaymentProvider())
                .withGatewayCredentialId(credentialParams.getId())
                .insert();

        Long refundAmount = 20L;

        worldpayMockClient.mockRefundSuccess();
        postRefundFor(testCharge.getExternalChargeId(), refundAmount, defaultTestCharge.getAmount())
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("reason", is("pending"))
                .body("message", contains(format("Charge with id [%s] not available for refund.", testCharge.getExternalChargeId())))
                .body("error_identifier", is(ErrorIdentifier.REFUND_NOT_AVAILABLE.toString()));

        List<Map<String, Object>> refundsFoundByChargeExternalId = databaseTestHelper.getRefundsByChargeExternalId(defaultTestCharge.getExternalChargeId());
        assertThat(refundsFoundByChargeExternalId.size(), is(0));
    }

    @Test
    public void shouldFailRequestingARefund_whenChargeRefundIsFull() {

        Long refundAmount = defaultTestCharge.getAmount();
        String externalChargeId = defaultTestCharge.getExternalChargeId();
        Long chargeId = defaultTestCharge.getChargeId();

        worldpayMockClient.mockRefundSuccess();
        postRefundFor(externalChargeId, refundAmount, defaultTestCharge.getAmount())
                .statusCode(ACCEPTED.getStatusCode());

        postRefundFor(externalChargeId, 1L, 0L)
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("reason", is("full"))
                .body("message", contains(format("Charge with id [%s] not available for refund.", externalChargeId)))
                .body("error_identifier", is(ErrorIdentifier.REFUND_NOT_AVAILABLE.toString()));

        List<Map<String, Object>> refundsFoundByChargeExternalId = databaseTestHelper.getRefundsByChargeExternalId(externalChargeId);
        assertThat(refundsFoundByChargeExternalId.size(), is(1));
    }

    @Test
    public void shouldFailRequestingARefund_whenAmountIsBiggerThanChargeAmount() {
        Long refundAmount = defaultTestCharge.getAmount() + 20;
        worldpayMockClient.mockRefundSuccess();
        postRefundFor(defaultTestCharge.getExternalChargeId(), refundAmount, defaultTestCharge.getAmount())
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("reason", is("amount_not_available"))
                .body("message", contains("Not sufficient amount available for refund"))
                .body("error_identifier", is(ErrorIdentifier.REFUND_NOT_AVAILABLE.toString()));

        List<Map<String, Object>> refundsFoundByChargeExternalId = databaseTestHelper.getRefundsByChargeExternalId(defaultTestCharge.getExternalChargeId());
        assertThat(refundsFoundByChargeExternalId.size(), is(0));
    }

    @Test
    public void shouldFailRequestingARefund_whenAmountIsBiggerThanAllowedChargeAmount() {

        Long refundAmount = 10000001L;

        postRefundFor(defaultTestCharge.getExternalChargeId(), refundAmount, defaultTestCharge.getAmount())
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("reason", is("amount_not_available"))
                .body("message", contains("Not sufficient amount available for refund"))
                .body("error_identifier", is(ErrorIdentifier.REFUND_NOT_AVAILABLE.toString()));

        List<Map<String, Object>> refundsFoundByChargeExternalId = databaseTestHelper.getRefundsByChargeExternalId(defaultTestCharge.getExternalChargeId());
        assertThat(refundsFoundByChargeExternalId.size(), is(0));
    }

    @Test
    public void shouldFailRequestingARefund_whenAmountIsLessThanOnePence() {

        Long refundAmount = 0L;

        postRefundFor(defaultTestCharge.getExternalChargeId(), refundAmount, defaultTestCharge.getAmount())
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("reason", is("amount_min_validation"))
                .body("message", contains("Validation error for amount. Minimum amount for a refund is 1"))
                .body("error_identifier", is(ErrorIdentifier.REFUND_NOT_AVAILABLE.toString()));

        List<Map<String, Object>> refundsFoundByChargeExternalId = databaseTestHelper.getRefundsByChargeExternalId(defaultTestCharge.getExternalChargeId());
        assertThat(refundsFoundByChargeExternalId.size(), is(0));
    }

    @Test
    public void shouldFailRequestingARefund_whenAPartialRefundMakesTotalRefundedAmountBiggerThanChargeAmount() {
        Long firstRefundAmount = 80L;
        Long secondRefundAmount = 30L; // 10 more than available

        worldpayMockClient.mockRefundSuccess();
        ValidatableResponse validatableResponse = postRefundFor(defaultTestCharge.getExternalChargeId(), firstRefundAmount, defaultTestCharge.getAmount());
        String firstRefundId = assertRefundResponseWith(firstRefundAmount, validatableResponse, ACCEPTED.getStatusCode());

        List<Map<String, Object>> refundsFoundByChargeExternalId = databaseTestHelper.getRefundsByChargeExternalId(defaultTestCharge.getExternalChargeId());
        assertThat(refundsFoundByChargeExternalId.size(), is(1));
        assertThat(refundsFoundByChargeExternalId, hasItems(aRefundMatching(firstRefundId, is(notNullValue()), defaultTestCharge.getExternalChargeId(), firstRefundAmount, "REFUND SUBMITTED")));

        worldpayMockClient.mockRefundSuccess();
        postRefundFor(defaultTestCharge.getExternalChargeId(), secondRefundAmount, defaultTestCharge.getAmount() - firstRefundAmount)
                .statusCode(400)
                .body("reason", is("amount_not_available"))
                .body("message", contains("Not sufficient amount available for refund"))
                .body("error_identifier", is(ErrorIdentifier.REFUND_NOT_AVAILABLE.toString()));

        List<Map<String, Object>> refundsFoundByChargeExternalId1 = databaseTestHelper.getRefundsByChargeExternalId(defaultTestCharge.getExternalChargeId());
        assertThat(refundsFoundByChargeExternalId1.size(), is(1));
        assertThat(refundsFoundByChargeExternalId1, hasItems(aRefundMatching(firstRefundId, is(notNullValue()), defaultTestCharge.getExternalChargeId(), firstRefundAmount, "REFUND SUBMITTED")));
    }

    @Test
    public void shouldFailRequestingARefund_whenGatewayOperationFails() {
        Long refundAmount = defaultTestCharge.getAmount();

        worldpayMockClient.mockRefundError();
        postRefundFor(defaultTestCharge.getExternalChargeId(), refundAmount, defaultTestCharge.getAmount())
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("message", contains("Worldpay refund response (error code: 2, error: Something went wrong.)"))
                .body("error_identifier", is(ErrorIdentifier.GENERIC.toString()));

        java.util.List<Map<String, Object>> refundsFoundByChargeExternalId = databaseTestHelper.getRefundsByChargeExternalId(defaultTestCharge.getExternalChargeId());
        assertThat(refundsFoundByChargeExternalId.size(), is(1));
        assertThat(refundsFoundByChargeExternalId, contains(
                allOf(
                        hasEntry("amount", (Object) refundAmount),
                        hasEntry("status", RefundStatus.REFUND_ERROR.getValue()),
                        hasEntry("charge_external_id", defaultTestCharge.getExternalChargeId())
                )));
    }

    @Test
    public void shouldBeAbleRetrieveARefund() {

        DatabaseFixtures.TestRefund testRefund = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestRefund()
                .withTestCharge(defaultTestCharge)
                .withType(RefundStatus.REFUND_SUBMITTED)
                .withGatewayTransactionId(randomAlphanumeric(10))
                .withChargeExternalId(defaultTestCharge.getExternalChargeId())
                .insert();

        ValidatableResponse validatableResponse = getRefundFor(
                defaultTestAccount.getAccountId(), defaultTestCharge.getExternalChargeId(), testRefund.getExternalRefundId());
        String refundId = assertRefundResponseWith(testRefund.getAmount(), validatableResponse, OK.getStatusCode());


        List<Map<String, Object>> refundsFoundByChargeExternalId = databaseTestHelper.getRefundsByChargeExternalId(defaultTestCharge.getExternalChargeId());
        assertThat(refundsFoundByChargeExternalId.size(), is(1));
        assertThat(refundsFoundByChargeExternalId, hasItems(aRefundMatching(refundId, is(notNullValue()), defaultTestCharge.getExternalChargeId(), testRefund.getAmount(), "REFUND SUBMITTED")));
    }

    @Test
    public void shouldBeAbleToRetrieveAllRefundsForACharge() {

        DatabaseFixtures.TestRefund testRefund1 = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestRefund()
                .withAmount(10L)
                .withCreatedDate(ZonedDateTime.of(2016, 8, 1, 0, 0, 0, 0, ZoneId.of("UTC")))
                .withTestCharge(defaultTestCharge)
                .withType(RefundStatus.REFUND_SUBMITTED)
                .withChargeExternalId(defaultTestCharge.getExternalChargeId())
                .insert();

        DatabaseFixtures.TestRefund testRefund2 = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestRefund()
                .withAmount(20L)
                .withCreatedDate(ZonedDateTime.of(2016, 8, 2, 0, 0, 0, 0, ZoneId.of("UTC")))
                .withTestCharge(defaultTestCharge)
                .withType(RefundStatus.REFUND_SUBMITTED)
                .withChargeExternalId(defaultTestCharge.getExternalChargeId())
                .insert();

        String paymentUrl = format("https://localhost:%s/v1/api/accounts/%s/charges/%s",
                testContext.getPort(), defaultTestAccount.getAccountId(), defaultTestCharge.getExternalChargeId());

        getRefundsFor(defaultTestAccount.getAccountId(),
                defaultTestCharge.getExternalChargeId())
                .statusCode(OK.getStatusCode())
                .body("payment_id", is(defaultTestCharge.getExternalChargeId()))
                .body("_links.self.href", is(paymentUrl + "/refunds"))
                .body("_links.payment.href", is(paymentUrl))
                .body("_embedded.refunds", hasSize(2))
                .body("_embedded.refunds[0].refund_id", is(testRefund1.getExternalRefundId()))
                .body("_embedded.refunds[0].amount", is(10))
                .body("_embedded.refunds[0].status", is("submitted"))
                .body("_embedded.refunds[0].created_date", is("2016-08-01T00:00:00.000Z"))
                .body("_embedded.refunds[0]._links.self.href", is(paymentUrl + "/refunds/" + testRefund1.getExternalRefundId()))
                .body("_embedded.refunds[0]._links.payment.href", is(paymentUrl))
                .body("_embedded.refunds[1].refund_id", is(testRefund2.getExternalRefundId()))
                .body("_embedded.refunds[1].amount", is(20))
                .body("_embedded.refunds[1].status", is("submitted"))
                .body("_embedded.refunds[1].created_date", is("2016-08-02T00:00:00.000Z"))
                .body("_embedded.refunds[1]._links.self.href", is(paymentUrl + "/refunds/" + testRefund2.getExternalRefundId()))
                .body("_embedded.refunds[1]._links.payment.href", is(paymentUrl));
    }

    @Test
    public void shouldFailRetrieveARefund_whenNonExistentAccountId() {
        DatabaseFixtures.TestRefund testRefund = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestRefund()
                .withTestCharge(defaultTestCharge)
                .withType(RefundStatus.REFUND_SUBMITTED)
                .insert();

        Long nonExistentAccountId = 999L;

        ValidatableResponse validatableResponse = getRefundFor(
                nonExistentAccountId, defaultTestCharge.getExternalChargeId(), testRefund.getExternalRefundId());

        validatableResponse.statusCode(NOT_FOUND.getStatusCode())
                .body("message", contains(format("Charge with id [%s] not found.", defaultTestCharge.getExternalChargeId())))
                .body("error_identifier", is(ErrorIdentifier.GENERIC.toString()));
    }

    @Test
    public void shouldFailRetrieveARefund_whenNonExistentChargeId() {
        DatabaseFixtures.TestRefund testRefund = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestRefund()
                .withTestCharge(defaultTestCharge)
                .withType(RefundStatus.REFUND_SUBMITTED)
                .insert();

        String nonExistentChargeId = "999";

        ValidatableResponse validatableResponse = getRefundFor(
                defaultTestAccount.getAccountId(), nonExistentChargeId, testRefund.getExternalRefundId());

        validatableResponse.statusCode(NOT_FOUND.getStatusCode())
                .body("message", contains(format("Charge with id [%s] not found.", nonExistentChargeId)))
                .body("error_identifier", is(ErrorIdentifier.GENERIC.toString()));
    }

    @Test
    public void shouldFailRetrieveARefund_whenNonExistentRefundId() {
        DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestRefund()
                .withTestCharge(defaultTestCharge)
                .withType(RefundStatus.REFUND_SUBMITTED)
                .insert();

        String nonExistentRefundId = "999";

        ValidatableResponse validatableResponse = getRefundFor(
                defaultTestAccount.getAccountId(), defaultTestCharge.getExternalChargeId(), nonExistentRefundId);

        validatableResponse.statusCode(NOT_FOUND.getStatusCode())
                .body("message", contains(format("Refund with id [%s] not found.", nonExistentRefundId)))
                .body("error_identifier", is(ErrorIdentifier.GENERIC.toString()));
    }

    private ValidatableResponse postRefundFor(String chargeId, Long refundAmount, Long refundAmountAvlbl) {
        ImmutableMap<String, Long> refundData = ImmutableMap.of("amount", refundAmount, "refund_amount_available", refundAmountAvlbl);
        String refundPayload = new Gson().toJson(refundData);

        return givenSetup()
                .body(refundPayload)
                .accept(ContentType.JSON)
                .contentType(ContentType.JSON)
                .post("/v1/api/accounts/{accountId}/charges/{chargeId}/refunds"
                        .replace("{accountId}", accountId)
                        .replace("{chargeId}", chargeId))
                .then();
    }

    private ValidatableResponse getRefundsFor(Long accountId, String chargeId) {
        return givenSetup()
                .accept(ContentType.JSON)
                .contentType(ContentType.JSON)
                .get("/v1/api/accounts/{accountId}/charges/{chargeId}/refunds"
                        .replace("{accountId}", accountId.toString())
                        .replace("{chargeId}", chargeId))
                .then();
    }

    private ValidatableResponse getRefundFor(Long accountId, String chargeId, String refundId) {
        return givenSetup()
                .accept(ContentType.JSON)
                .contentType(ContentType.JSON)
                .get("/v1/api/accounts/{accountId}/charges/{chargeId}/refunds/{refundId}"
                        .replace("{accountId}", accountId.toString())
                        .replace("{chargeId}", chargeId)
                        .replace("{refundId}", refundId))
                .then();
    }

    private String assertRefundResponseWith(Long refundAmount, ValidatableResponse validatableResponse, int expectedStatusCode) {
        ValidatableResponse response = validatableResponse
                .statusCode(expectedStatusCode)
                .body("refund_id", is(notNullValue()))
                .body("amount", is(refundAmount.intValue()))
                .body("status", is("submitted"))
                .body("created_date", is(notNullValue()));

        String paymentUrl = format("https://localhost:%s/v1/api/accounts/%s/charges/%s",
                testContext.getPort(), defaultTestAccount.getAccountId(), defaultTestCharge.getExternalChargeId());

        String refundId = response.extract().path("refund_id");
        response.body("_links.self.href", is(paymentUrl + "/refunds/" + refundId))
                .body("_links.payment.href", is(paymentUrl));

        return refundId;
    }

}
