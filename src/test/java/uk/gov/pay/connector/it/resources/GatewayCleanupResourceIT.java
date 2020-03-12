package uk.gov.pay.connector.it.resources;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.testcontainers.shaded.org.apache.commons.lang.math.RandomUtils;
import uk.gov.pay.connector.app.ConnectorApp;
import uk.gov.pay.connector.it.base.ChargingITestBase;
import uk.gov.pay.connector.junit.DropwizardConfig;
import uk.gov.pay.connector.junit.DropwizardJUnitRunner;

import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static javax.ws.rs.core.Response.Status.OK;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_ERROR;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_ERROR_CANCELLED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_REJECTED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_TIMEOUT;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_UNEXPECTED_ERROR;
import static uk.gov.pay.connector.gateway.PaymentGatewayName.WORLDPAY;
import static uk.gov.pay.connector.it.dao.DatabaseFixtures.withDatabaseTestHelper;
import static uk.gov.pay.connector.util.AddChargeParams.AddChargeParamsBuilder.anAddChargeParams;

@RunWith(DropwizardJUnitRunner.class)
@DropwizardConfig(app = ConnectorApp.class, config = "config/test-it-config.yaml")
public class GatewayCleanupResourceIT extends ChargingITestBase {

    private static final String PROVIDER_NAME = "epdq";

    public GatewayCleanupResourceIT() {
        super(PROVIDER_NAME);
    }

    @Test
    public void shouldCleanUpChargesInAuthorisationErrorStates() {
        String chargeId1 = addCharge(AUTHORISATION_REJECTED);
        String chargeId2 = addCharge(AUTHORISATION_ERROR);
        String chargeId3 = addCharge(AUTHORISATION_UNEXPECTED_ERROR);
        String chargeId4 = addCharge(AUTHORISATION_TIMEOUT);

        // add a non-ePDQ charge that shouldn't be picked up
        var worldpayAccount = withDatabaseTestHelper(databaseTestHelper)
                .aTestAccount()
                .withAccountId(RandomUtils.nextLong())
                .withPaymentProvider(WORLDPAY.getName())
                .insert();

        databaseTestHelper.addCharge(anAddChargeParams()
                .withGatewayAccountId(String.valueOf(worldpayAccount.getAccountId()))
                .withStatus(AUTHORISATION_ERROR)
                .build());

        epdqMockClient.mockCancelSuccess();
        epdqMockClient.mockAuthorisationQuerySuccess();

        given().port(testContext.getPort())
                .post("/v1/tasks/gateway-cleanup-sweep?limit=10")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(JSON)
                .body("cleanup-success", is(3))
                .body("cleanup-failed", is(0));

        List<String> events1 = databaseTestHelper.getInternalEvents(chargeId1);
        List<String> events2 = databaseTestHelper.getInternalEvents(chargeId2);
        List<String> events3 = databaseTestHelper.getInternalEvents(chargeId3);
        List<String> events4 = databaseTestHelper.getInternalEvents(chargeId4);

        assertThat(events1, contains(AUTHORISATION_REJECTED.getValue()));
        assertThat(events2, contains(AUTHORISATION_ERROR.getValue(), AUTHORISATION_ERROR_CANCELLED.getValue()));
        assertThat(events3, contains(AUTHORISATION_UNEXPECTED_ERROR.getValue(), AUTHORISATION_ERROR_CANCELLED.getValue()));
        assertThat(events4, contains(AUTHORISATION_TIMEOUT.getValue(), AUTHORISATION_ERROR_CANCELLED.getValue()));
    }

    @Test
    public void shouldLimitChargesCleanedUp() {
        addCharge(AUTHORISATION_ERROR);
        addCharge(AUTHORISATION_ERROR);
        addCharge(AUTHORISATION_ERROR);

        epdqMockClient.mockCancelSuccess();
        epdqMockClient.mockAuthorisationQuerySuccess();

        given().port(testContext.getPort())
                .post("/v1/tasks/gateway-cleanup-sweep?limit=2")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(JSON)
                .body("cleanup-success", is(2))
                .body("cleanup-failed", is(0));
    }

    @Test
    public void shouldReturn422WhenLimitQueryParamMissing() {
        given().port(testContext.getPort())
                .post("/v1/tasks/gateway-cleanup-sweep")
                .then()
                .statusCode(422)
                .contentType(JSON)
                .body("message", containsInAnyOrder("Parameter [limit] is required"));
    }
}
