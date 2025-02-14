package uk.gov.pay.connector.wallets;

import com.codahale.metrics.MetricRegistry;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import io.dropwizard.setup.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.charge.model.domain.Auth3dsRequiredEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeStatus;
import uk.gov.pay.connector.charge.service.ChargeService;
import uk.gov.pay.connector.gateway.GatewayException;
import uk.gov.pay.connector.gateway.GatewayException.GatewayErrorException;
import uk.gov.pay.connector.gateway.PaymentProvider;
import uk.gov.pay.connector.gateway.PaymentProviders;
import uk.gov.pay.connector.gateway.model.AuthCardDetails;
import uk.gov.pay.connector.gateway.model.ProviderSessionIdentifier;
import uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse;
import uk.gov.pay.connector.gateway.model.response.GatewayResponse;
import uk.gov.pay.connector.logging.AuthorisationLogger;
import uk.gov.pay.connector.paymentprocessor.model.OperationType;
import uk.gov.pay.connector.paymentprocessor.service.AuthorisationService;
import uk.gov.service.payments.commons.model.CardExpiryDate;

import java.util.Optional;

import static java.lang.String.format;

public class WalletAuthoriseService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(WalletAuthoriseService.class);
    
    private final AuthorisationService authorisationService;
    private final ChargeService chargeService;
    private final PaymentProviders paymentProviders;
    private final WalletAuthorisationRequestToAuthCardDetailsConverter walletAuthorisationDataToAuthCardDetailsConverter;
    private final AuthorisationLogger authorisationLogger;
    private MetricRegistry metricRegistry;

    @Inject
    public WalletAuthoriseService(PaymentProviders paymentProviders,
                                  ChargeService chargeService,
                                  AuthorisationService authorisationService,
                                  WalletAuthorisationRequestToAuthCardDetailsConverter walletAuthorisationDataToAuthCardDetailsConverter,
                                  AuthorisationLogger authorisationLogger, 
                                  Environment environment) {
        this.paymentProviders = paymentProviders;
        this.authorisationService = authorisationService;
        this.walletAuthorisationDataToAuthCardDetailsConverter = walletAuthorisationDataToAuthCardDetailsConverter;
        this.chargeService = chargeService;
        this.authorisationLogger = authorisationLogger;
        this.metricRegistry = environment.metrics();
    }

    public GatewayResponse<BaseAuthoriseResponse> doAuthorise(String chargeId, WalletAuthorisationRequest walletAuthorisationRequest) {
        return authorisationService.executeAuthorise(chargeId, () -> {
            final ChargeEntity charge = prepareChargeForAuthorisation(chargeId);
            GatewayResponse<BaseAuthoriseResponse> operationResponse;
            ChargeStatus chargeStatus = null;
            String requestStatus = "failure";

            try {
                operationResponse = authorise(charge, walletAuthorisationRequest);

                if (operationResponse.getBaseResponse().isPresent()) {
                    requestStatus = "success";
                    chargeStatus = operationResponse.getBaseResponse().get().authoriseStatus().getMappedChargeStatus();
                } else {
                    operationResponse.throwGatewayError();
                }

            } catch (GatewayException e) {

                LOGGER.info("Error occurred authorising charge. Charge external id: {}; message: {}", charge.getExternalId(), e.getMessage());

                if (e instanceof GatewayErrorException) {
                    LOGGER.error("Response from gateway: {}", ((GatewayErrorException) e).getResponseFromGateway());
                }

                chargeStatus = AuthorisationService.mapFromGatewayErrorException(e);
                operationResponse = GatewayResponse.GatewayResponseBuilder.responseBuilder().withGatewayError(e.toGatewayError()).build();
            }

            Optional<String> transactionId = authorisationService.extractTransactionId(charge.getExternalId(), operationResponse, charge.getGatewayTransactionId());
            Optional<ProviderSessionIdentifier> sessionIdentifier = operationResponse.getSessionIdentifier();
            Optional<Auth3dsRequiredEntity> auth3dsDetailsEntity =
                    operationResponse.getBaseResponse().flatMap(BaseAuthoriseResponse::extractAuth3dsRequiredDetails);
            CardExpiryDate cardExpiryDate = operationResponse.getBaseResponse().flatMap(BaseAuthoriseResponse::getCardExpiryDate).orElse(null);
            
            logMetrics(charge, operationResponse, requestStatus, walletAuthorisationRequest.getWalletType());

            processGatewayAuthorisationResponse(
                    charge.getExternalId(),
                    walletAuthorisationRequest,
                    transactionId.orElse(null),
                    sessionIdentifier.orElse(null),
                    chargeStatus,
                    auth3dsDetailsEntity,
                    cardExpiryDate);
            
            authorisationLogger.logChargeAuthorisation(
                    LOGGER,
                    charge,
                    transactionId.orElse("missing transaction ID"),
                    operationResponse,
                    charge.getChargeStatus(),
                    chargeStatus
            );

            return operationResponse;
        });
    }

    private void logMetrics(ChargeEntity chargeEntity,
                            GatewayResponse<BaseAuthoriseResponse> operationResponse,
                            String successOrFailure,
                            WalletType walletType) {

        LOGGER.info("{} authorisation {} - charge_external_id={}, payment provider response={}",
                walletType.toString(), successOrFailure, chargeEntity.getExternalId(), operationResponse.toString());
        metricRegistry.counter(format("gateway-operations.%s.%s.authorise.%s.result.%s",
                chargeEntity.getPaymentProvider(),
                chargeEntity.getGatewayAccount().getType(),
                walletType.equals(WalletType.GOOGLE_PAY) ? "google-pay" : "apple-pay",
                successOrFailure)).inc();
    }

    @Transactional
    private ChargeEntity prepareChargeForAuthorisation(String chargeId) {
        ChargeEntity charge = chargeService.lockChargeForProcessing(chargeId, OperationType.AUTHORISATION);
        getPaymentProviderFor(charge)
                .generateTransactionId()
                .ifPresent(charge::setGatewayTransactionId);
        return charge;
    }

    private void processGatewayAuthorisationResponse(
            String chargeExternalId,
            WalletAuthorisationRequest walletAuthorisationRequest,
            String transactionId,
            ProviderSessionIdentifier sessionIdentifier,
            ChargeStatus status,
            Optional<Auth3dsRequiredEntity> auth3dsRequiredDetails,
            CardExpiryDate cardExpiryDate) {
        
        LOGGER.info("Processing gateway auth response for {}", walletAuthorisationRequest.getWalletType().toString());
        
        AuthCardDetails authCardDetailsToBePersisted = walletAuthorisationDataToAuthCardDetailsConverter.convert(walletAuthorisationRequest, cardExpiryDate);
        ChargeEntity updatedCharge = chargeService.updateChargePostWalletAuthorisation(
                chargeExternalId,
                status,
                transactionId,
                sessionIdentifier,
                authCardDetailsToBePersisted,
                walletAuthorisationRequest.getWalletType(),
                walletAuthorisationRequest.getPaymentInfo().getEmail(),
                auth3dsRequiredDetails);

        metricRegistry.counter(String.format(
                "gateway-operations.%s.%s.%s.authorise.result.%s",
                updatedCharge.getPaymentProvider(),
                updatedCharge.getGatewayAccount().getType(),
                updatedCharge.getGatewayAccount().getId(),
                status.toString())).inc();
    }

    private GatewayResponse<BaseAuthoriseResponse> authorise(ChargeEntity chargeEntity, WalletAuthorisationRequest walletAuthorisationRequest)
            throws GatewayException {
        
        LOGGER.info("Authorising charge for {}", walletAuthorisationRequest.getWalletType().toString());
        
        var authorisationGatewayRequest = WalletAuthorisationGatewayRequest.valueOf(chargeEntity, walletAuthorisationRequest);
        return getPaymentProviderFor(chargeEntity).authoriseWallet(authorisationGatewayRequest);
    }

    private PaymentProvider getPaymentProviderFor(ChargeEntity chargeEntity) {
        return paymentProviders.byName(chargeEntity.getPaymentGatewayName());
    }
}
