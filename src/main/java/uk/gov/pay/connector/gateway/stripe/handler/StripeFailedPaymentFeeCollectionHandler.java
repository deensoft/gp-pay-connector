package uk.gov.pay.connector.gateway.stripe.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.app.StripeGatewayConfig;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.FeeType;
import uk.gov.pay.connector.charge.util.StripeFeeCalculator;
import uk.gov.pay.connector.fee.model.Fee;
import uk.gov.pay.connector.gateway.GatewayClient;
import uk.gov.pay.connector.gateway.GatewayException;
import uk.gov.pay.connector.gateway.model.request.GatewayClientRequest;
import uk.gov.pay.connector.gateway.stripe.json.StripeCharge;
import uk.gov.pay.connector.gateway.stripe.json.StripePaymentIntent;
import uk.gov.pay.connector.gateway.stripe.json.StripeTransferResponse;
import uk.gov.pay.connector.gateway.stripe.request.StripeGetPaymentIntentRequest;
import uk.gov.pay.connector.gateway.stripe.request.StripeTransferInRequest;
import uk.gov.pay.connector.util.JsonObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class StripeFailedPaymentFeeCollectionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StripeFailedPaymentFeeCollectionHandler.class);

    private final GatewayClient client;
    private final StripeGatewayConfig stripeGatewayConfig;
    private final JsonObjectMapper jsonObjectMapper;
    
    public StripeFailedPaymentFeeCollectionHandler(
            GatewayClient client,
            StripeGatewayConfig stripeGatewayConfig,
            JsonObjectMapper jsonObjectMapper) {
        this.client = client;
        this.stripeGatewayConfig = stripeGatewayConfig;
        this.jsonObjectMapper = jsonObjectMapper;
    }
    
    public List<Fee> calculateAndTransferFees(ChargeEntity charge) throws GatewayException {
        // TODO: no Stripe charge exists if the 3DS attempt is failed, so this method of determining whether a payment has been through 3DS will need to change
        boolean threeDsFeeApplicable = getStripeCharge(charge)
                .map(this::isThreeDsFeeApplicable)
                .orElse(false);

        List<Fee> fees = new ArrayList<>();
        fees.add(Fee.of(FeeType.RADAR, (long) stripeGatewayConfig.getRadarFeeInPence()));
        if (threeDsFeeApplicable) {
            fees.add(Fee.of(FeeType.THREE_D_S, (long) stripeGatewayConfig.getThreeDsFeeInPence()));
        }
        Long feeAmount = StripeFeeCalculator.getTotalFeeAmount(fees);

        transferFeeFromConnectAccount(feeAmount, charge);

        return fees;

    }

    private boolean isThreeDsFeeApplicable(StripeCharge stripeCharge) {
        return stripeCharge.getPaymentMethodDetails() != null &&
                stripeCharge.getPaymentMethodDetails().getCard() != null &&
                stripeCharge.getPaymentMethodDetails().getCard().getThreeDSecure() != null &&
                stripeCharge.getPaymentMethodDetails().getCard().getThreeDSecure().getAuthenticated();
    }

    private Optional<StripeCharge> getStripeCharge(ChargeEntity charge) throws GatewayException.GatewayErrorException, GatewayException.GenericGatewayException, GatewayException.GatewayConnectionTimeoutException {
        StripePaymentIntent paymentIntent = getPaymentIntent(charge);
        List<StripeCharge> charges = paymentIntent.getChargesCollection().getCharges();
        if (charges.size() > 1) {
            throw new RuntimeException("Expected at most 1 Charge for PaymentIntent, found " + charges.size());
        }
        return charges.stream().findFirst();
    }

    private StripePaymentIntent getPaymentIntent(ChargeEntity charge)
            throws GatewayException.GatewayErrorException, GatewayException.GenericGatewayException, GatewayException.GatewayConnectionTimeoutException {
        GatewayClientRequest request = StripeGetPaymentIntentRequest.of(charge, stripeGatewayConfig);
        String rawResponse = client.getRequestFor(request).getEntity();
        return jsonObjectMapper.getObject(rawResponse, StripePaymentIntent.class);
    }

    private void transferFeeFromConnectAccount(long feeAmount,
                                               ChargeEntity charge)
            throws GatewayException.GatewayErrorException, GatewayException.GenericGatewayException, GatewayException.GatewayConnectionTimeoutException {
        StripeTransferInRequest transferInRequest = StripeTransferInRequest.of(
                String.valueOf(feeAmount),
                charge.getGatewayAccount(),
                charge.getGatewayAccountCredentialsEntity(),
                charge.getGatewayTransactionId(),
                charge.getExternalId(),
                stripeGatewayConfig);
        String rawResponse = client.postRequestFor(transferInRequest).getEntity();
        StripeTransferResponse stripeTransferResponse = jsonObjectMapper.getObject(rawResponse, StripeTransferResponse.class);

        LOGGER.info("To collect fees for failed payment {}, transferred net amount {} - transfer id {} - from Stripe Connect account id {} in transfer group {}",
                charge.getExternalId(),
                feeAmount,
                stripeTransferResponse.getId(),
                stripeTransferResponse.getDestinationStripeAccountId(),
                stripeTransferResponse.getStripeTransferGroup()
        );
    }
}
