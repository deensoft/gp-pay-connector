package uk.gov.pay.connector.gatewayaccountcredentials.service;

import com.google.inject.persist.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.common.model.api.jsonpatch.JsonPatchOp;
import uk.gov.pay.connector.common.model.api.jsonpatch.JsonPatchRequest;
import uk.gov.pay.connector.gateway.PaymentGatewayName;
import uk.gov.pay.connector.gatewayaccount.exception.GatewayAccountCredentialsNotFoundException;
import uk.gov.pay.connector.gatewayaccount.exception.GatewayAccountNotFoundException;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountCredentials;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.gatewayaccountcredentials.dao.GatewayAccountCredentialsDao;
import uk.gov.pay.connector.gatewayaccountcredentials.exception.GatewayAccountCredentialsNotConfiguredException;
import uk.gov.pay.connector.gatewayaccountcredentials.exception.NoCredentialsExistForProviderException;
import uk.gov.pay.connector.gatewayaccountcredentials.exception.NoCredentialsInUsableStateException;
import uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialState;
import uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialsEntity;

import javax.inject.Inject;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static net.logstash.logback.argument.StructuredArguments.kv;
import static uk.gov.pay.connector.gateway.PaymentGatewayName.SANDBOX;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialState.ACTIVE;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialState.CREATED;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialState.ENTERED;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialState.VERIFIED_WITH_LIVE_PAYMENT;
import static uk.gov.pay.connector.gatewayaccountcredentials.resource.GatewayAccountCredentialsRequestValidator.FIELD_CREDENTIALS;
import static uk.gov.pay.connector.gatewayaccountcredentials.resource.GatewayAccountCredentialsRequestValidator.FIELD_GATEWAY_MERCHANT_ID;
import static uk.gov.pay.connector.gatewayaccountcredentials.resource.GatewayAccountCredentialsRequestValidator.FIELD_LAST_UPDATED_BY_USER;
import static uk.gov.pay.connector.gatewayaccountcredentials.resource.GatewayAccountCredentialsRequestValidator.FIELD_STATE;
import static uk.gov.pay.connector.gatewayaccountcredentials.resource.GatewayAccountCredentialsRequestValidator.GATEWAY_MERCHANT_ID_PATH;
import static uk.gov.pay.connector.util.RandomIdGenerator.randomUuid;
import static uk.gov.pay.connector.util.ResponseUtil.badRequestResponse;
import static uk.gov.pay.connector.util.ResponseUtil.serviceErrorResponse;
import static uk.gov.service.payments.logging.LoggingKeys.GATEWAY_ACCOUNT_ID;
import static uk.gov.service.payments.logging.LoggingKeys.GATEWAY_ACCOUNT_TYPE;
import static uk.gov.service.payments.logging.LoggingKeys.PROVIDER;
import static uk.gov.service.payments.logging.LoggingKeys.USER_EXTERNAL_ID;

public class GatewayAccountCredentialsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayAccountCredentialsService.class);

    private static final String GATEWAY_MERCHANT_ID = "gateway_merchant_id";
    private final GatewayAccountCredentialsDao gatewayAccountCredentialsDao;

    private final Set<GatewayAccountCredentialState> USABLE_STATES = EnumSet.of(ENTERED, VERIFIED_WITH_LIVE_PAYMENT, ACTIVE);

    @Inject
    public GatewayAccountCredentialsService(GatewayAccountCredentialsDao gatewayAccountCredentialsDao) {
        this.gatewayAccountCredentialsDao = gatewayAccountCredentialsDao;
    }

    @Transactional
    public GatewayAccountCredentials createGatewayAccountCredentials(GatewayAccountEntity gatewayAccountEntity,
                                                                     String paymentProvider,
                                                                     Map<String, String> credentials) {
        GatewayAccountCredentialState state = calculateStateForNewCredentials(gatewayAccountEntity, paymentProvider, credentials);
        GatewayAccountCredentialsEntity gatewayAccountCredentialsEntity
                = new GatewayAccountCredentialsEntity(gatewayAccountEntity, paymentProvider, credentials, state);

        if (state == ACTIVE) {
            gatewayAccountCredentialsEntity.setActiveStartDate(Instant.now());
        }
        gatewayAccountCredentialsEntity.setExternalId(randomUuid());

        gatewayAccountCredentialsDao.persist(gatewayAccountCredentialsEntity);
        return new GatewayAccountCredentials(gatewayAccountCredentialsEntity);
    }

    private GatewayAccountCredentialState calculateStateForNewCredentials(GatewayAccountEntity gatewayAccountEntity,
                                                                          String paymentProvider, Map<String, String> credentials) {
        PaymentGatewayName paymentGatewayName = PaymentGatewayName.valueFrom(paymentProvider);
        boolean isFirstCredentials = !gatewayAccountCredentialsDao.hasActiveCredentials(gatewayAccountEntity.getId());
        boolean credentialsPrePopulated = !credentials.isEmpty();

        if ((isFirstCredentials && credentialsPrePopulated) || paymentGatewayName == SANDBOX) {
            return ACTIVE;
        }

        return credentialsPrePopulated ? ENTERED : CREATED;
    }

    @Transactional
    public GatewayAccountCredentials updateGatewayAccountCredentials(
            GatewayAccountCredentialsEntity gatewayAccountCredentialsEntity,
            Iterable<JsonPatchRequest> updateRequests) {
        for (JsonPatchRequest updateRequest : updateRequests) {
            if (JsonPatchOp.REPLACE == updateRequest.getOp()) {
                updateGatewayAccountCredentialField(updateRequest, gatewayAccountCredentialsEntity);
            }
        }
        gatewayAccountCredentialsDao.merge(gatewayAccountCredentialsEntity);

        GatewayAccountEntity gatewayAccountEntity = gatewayAccountCredentialsEntity.getGatewayAccountEntity();
        LOGGER.info("Updated credentials for gateway account [id={}]", gatewayAccountEntity.getId(),
                kv(GATEWAY_ACCOUNT_ID, gatewayAccountEntity.getId()),
                kv(GATEWAY_ACCOUNT_TYPE, gatewayAccountEntity.getType()),
                kv(PROVIDER, gatewayAccountCredentialsEntity.getPaymentProvider()),
                kv("state", gatewayAccountCredentialsEntity.getState()),
                kv(USER_EXTERNAL_ID, gatewayAccountCredentialsEntity.getLastUpdatedByUserExternalId())
        );

        return new GatewayAccountCredentials(gatewayAccountCredentialsEntity);
    }

    private void updateGatewayAccountCredentialField(JsonPatchRequest patchRequest,
                                                     GatewayAccountCredentialsEntity gatewayAccountCredentialsEntity) {
        switch (patchRequest.getPath()) {
            case FIELD_CREDENTIALS:
                updateCredentials(patchRequest, gatewayAccountCredentialsEntity);
                break;
            case FIELD_LAST_UPDATED_BY_USER:
                gatewayAccountCredentialsEntity.setLastUpdatedByUserExternalId(patchRequest.valueAsString());
                break;
            case FIELD_STATE:
                gatewayAccountCredentialsEntity.setState(
                        GatewayAccountCredentialState.valueOf(patchRequest.valueAsString()));
                break;
            case GATEWAY_MERCHANT_ID_PATH:
                HashMap<String, String> updatableMap = new HashMap<>(gatewayAccountCredentialsEntity.getCredentials());
                updatableMap.put(FIELD_GATEWAY_MERCHANT_ID, patchRequest.valueAsString());
                gatewayAccountCredentialsEntity.setCredentials(updatableMap);
                break;
            default:
                throw new BadRequestException("Unexpected path for patch operation: " + patchRequest.getPath());
        }
    }

    private void updateCredentials(JsonPatchRequest patchRequest, GatewayAccountCredentialsEntity gatewayAccountCredentialsEntity) {
        HashMap<String, String> updatableMap = new HashMap<>(gatewayAccountCredentialsEntity.getCredentials());
        patchRequest.valueAsObject().forEach(updatableMap::put);
        gatewayAccountCredentialsEntity.setCredentials(updatableMap);
        if (gatewayAccountCredentialsEntity.getState() == CREATED) {
            updateStateForEnteredCredentials(gatewayAccountCredentialsEntity);
        }
    }

    private void updateStateForEnteredCredentials(GatewayAccountCredentialsEntity credentialsEntity) {
        if (credentialsEntity.getGatewayAccountEntity().getGatewayAccountCredentials().size() == 1) {
            credentialsEntity.setState(ACTIVE);
            credentialsEntity.setActiveStartDate(Instant.now());
        } else {
            credentialsEntity.setState(ENTERED);
        }
    }

    @Transactional
    public GatewayAccountEntity findStripeGatewayAccountForCredentialKeyAndValue(String stripeAccountIdKey, String stripeAccountId) {
        GatewayAccountCredentialsEntity gatewayAccountCredentialsEntity = gatewayAccountCredentialsDao
                .findByCredentialsKeyValue(stripeAccountIdKey, stripeAccountId)
                .orElseThrow(() -> new GatewayAccountCredentialsNotFoundException(format("Gateway account credentials with Stripe connect account ID [%s] not found.", stripeAccountId)));

        return Optional.ofNullable(gatewayAccountCredentialsEntity)
                .map(entity -> gatewayAccountCredentialsEntity.getGatewayAccountEntity())
                .orElseThrow(() -> new GatewayAccountNotFoundException(format("Gateway account with Stripe connect account ID [%s] not found.", stripeAccountId)));
    }

    public GatewayAccountCredentialsEntity getUsableCredentialsForProvider(GatewayAccountEntity gatewayAccountEntity, String paymentProvider) {
        List<GatewayAccountCredentialsEntity> credentialsForProvider = gatewayAccountEntity.getGatewayAccountCredentials()
                .stream()
                .filter(gatewayAccountCredentialsEntity -> gatewayAccountCredentialsEntity.getPaymentProvider().equals(paymentProvider))
                .collect(Collectors.toList());

        if (credentialsForProvider.isEmpty()) {
            throw new NoCredentialsExistForProviderException(paymentProvider);
        }

        if (credentialsForProvider.size() == 1 && CREATED.equals(credentialsForProvider.get(0).getState())) {
            throw new GatewayAccountCredentialsNotConfiguredException();
        }

        List<GatewayAccountCredentialsEntity> credentialsInState = credentialsForProvider
                .stream()
                .filter(gatewayAccountCredentialsEntity -> USABLE_STATES.contains(gatewayAccountCredentialsEntity.getState()))
                .collect(Collectors.toList());

        if (credentialsInState.isEmpty()) {
            throw new NoCredentialsInUsableStateException(paymentProvider);
        }
        if (credentialsInState.size() > 1) {
            throw new WebApplicationException(badRequestResponse(Collections.singletonList("Multiple usable credentials exist for payment provider [%s], unable to determine which to use.")));
        }
        return credentialsInState.get(0);
    }

    public GatewayAccountCredentialsEntity getCurrentOrActiveCredential(GatewayAccountEntity gatewayAccountEntity) {
        return gatewayAccountEntity.getCurrentOrActiveGatewayAccountCredential()
                .orElseThrow(() -> new WebApplicationException(
                        serviceErrorResponse(format("Active or current credential not found for gateway account [%s]",
                                gatewayAccountEntity.getId()))));
    }

    public boolean hasActiveCredentials(Long gatewayAccountId) {
        return gatewayAccountCredentialsDao.hasActiveCredentials(gatewayAccountId);
    }
}
