package uk.gov.pay.connector.service;

import com.google.inject.persist.Transactional;
import fj.data.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.dao.ChargeJpaDao;
import uk.gov.pay.connector.dao.GatewayAccountJpaDao;
import uk.gov.pay.connector.model.*;
import uk.gov.pay.connector.model.domain.Card;
import uk.gov.pay.connector.model.domain.ChargeEntity;
import uk.gov.pay.connector.model.domain.ChargeStatus;

import javax.inject.Inject;
import javax.persistence.OptimisticLockException;
import java.util.Arrays;
import java.util.function.Function;
import java.util.function.Supplier;

import static fj.data.Either.left;
import static fj.data.Either.right;
import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.equalsIgnoreCase;
import static uk.gov.pay.connector.model.GatewayError.*;
import static uk.gov.pay.connector.model.GatewayError.illegalStateError;
import static uk.gov.pay.connector.model.GatewayError.operationAlreadyInProgress;
import static uk.gov.pay.connector.model.GatewayErrorType.CHARGE_NOT_FOUND;
import static uk.gov.pay.connector.model.domain.ChargeStatus.*;

public class CardService {
    private final Logger logger = LoggerFactory.getLogger(CardService.class);

    private static final ChargeStatus[] CANCELLABLE_STATES = new ChargeStatus[]{
            CREATED, ENTERING_CARD_DETAILS, AUTHORISATION_SUCCESS, AUTHORISATION_READY, READY_FOR_CAPTURE
    };

    private final GatewayAccountJpaDao accountDao;
    private final ChargeJpaDao chargeDao;
    private final PaymentProviders providers;

    @Inject
    public CardService(GatewayAccountJpaDao accountDao, ChargeJpaDao chargeDao, PaymentProviders providers) {
        this.accountDao = accountDao;
        this.chargeDao = chargeDao;
        this.providers = providers;
    }

    public Either<GatewayError, GatewayResponse> doAuthorise(Long chargeId, Card cardDetails) {

        Function<ChargeEntity, Either<GatewayError, GatewayResponse>> doAuthorise =
                (charge) -> {
                    Either<GatewayError, ChargeEntity> preAuthorised = null;

                    try {
                        preAuthorised = preAuthorise(charge);
                    } catch (OptimisticLockException e) {
                        return left(conflictError(format("Authorisation for charge conflicting, %s", chargeId)));
                    }

                    if (preAuthorised.isLeft())
                        return left(preAuthorised.left().value());

                    Either<GatewayError, AuthorisationResponse> authorised =
                            authorise(preAuthorised.right().value(), cardDetails);
                    if (authorised.isLeft())
                        return left(authorised.left().value());

                    Either<GatewayError, GatewayResponse> postAuthorised =
                            postAuthorise(preAuthorised.right().value(), authorised.right().value());
                    if (postAuthorised.isLeft())
                        return left(postAuthorised.left().value());

                    return right(authorised.right().value());
                };

        return chargeDao
                .findById(chargeId)
                .map(doAuthorise)
                .orElseGet(chargeNotFound(chargeId));
    }

    @Transactional
    public Either<GatewayError, ChargeEntity> preAuthorise(ChargeEntity charge) {
        ChargeEntity reloadedCharge = chargeDao.merge(charge);
        if (!hasStatus(reloadedCharge, ENTERING_CARD_DETAILS)) {
            if (hasStatus(reloadedCharge, AUTHORISATION_READY)) {
                return left(operationAlreadyInProgress(format("Authorisation for charge already in progress, %s",
                        reloadedCharge.getId())));
            }
            logger.error(format("Charge with id [%s] and with status [%s] should be in [ENTERING CARD DETAILS] for authorisation.",
                    reloadedCharge.getId(), reloadedCharge.getStatus()));
            return left(illegalStateError(format("Charge not in correct state to be processed, %s", reloadedCharge.getId())));
        }
        reloadedCharge.setStatus(AUTHORISATION_READY);

        return right(reloadedCharge);
    }

    private Either<GatewayError, AuthorisationResponse> authorise(ChargeEntity charge, Card cardDetails) {
        AuthorisationRequest request = new AuthorisationRequest(charge, cardDetails);
        AuthorisationResponse response = paymentProviderFor(charge)
                .authorise(request);
        return right(response);
    }

    @Transactional
    public Either<GatewayError, GatewayResponse> postAuthorise(ChargeEntity charge, AuthorisationResponse response) {
        ChargeEntity reloadedCharge = chargeDao.merge(charge);
        reloadedCharge.setStatus(response.getNewChargeStatus());
        reloadedCharge.setGatewayTransactionId(response.getTransactionId());

        return right(response);
    }

    public Either<GatewayError, GatewayResponse> doCapture(Long chargeId) {
        return chargeDao
                .findById(Long.valueOf(chargeId))
                .map(capture())
                .orElseGet(chargeNotFound(chargeId));
    }

    public Either<GatewayError, GatewayResponse> doCancel(Long chargeId, Long accountId) {
        return chargeDao
                .findChargeForAccount(Long.valueOf(chargeId), accountId)
                .map(cancel())
                .orElseGet(chargeNotFound(chargeId));
    }

    private Function<ChargeEntity, Either<GatewayError, GatewayResponse>> capture() {
        return charge -> hasStatus(charge, AUTHORISATION_SUCCESS) ?
                right(captureFor(charge)) :
                left(captureErrorMessageFor(charge.getStatus()));
    }

    private Function<ChargeEntity, Either<GatewayError, GatewayResponse>> cancel() {
        return charge -> hasStatus(charge, CANCELLABLE_STATES) ?
                right(cancelFor(charge)) :
                left(cancelErrorMessageFor(charge.getId(), charge.getStatus()));
    }

    private GatewayResponse captureFor(ChargeEntity charge) {
        CaptureRequest request = CaptureRequest.valueOf(charge);
        CaptureResponse response = paymentProviderFor(charge)
                .capture(request);

        ChargeStatus newStatus =
                response.isSuccessful() ?
                        CAPTURE_SUBMITTED :
                        CAPTURE_UNKNOWN;

        charge.setStatus(newStatus);
        chargeDao.mergeChargeEntityWithChangedStatus(charge);

        return response;
    }

    private GatewayResponse cancelFor(ChargeEntity charge) {
        CancelRequest request = CancelRequest.valueOf(charge);
        CancelResponse response = paymentProviderFor(charge).cancel(request);

        if (response.isSuccessful()) {
            charge.setStatus(SYSTEM_CANCELLED);
            chargeDao.mergeChargeEntityWithChangedStatus(charge);
        }
        return response;
    }

    private PaymentProvider paymentProviderFor(ChargeEntity charge) {
        return providers.resolve(charge.getGatewayAccount().getGatewayName());
    }

    private boolean hasStatus(ChargeEntity charge, ChargeStatus... states) {
        return Arrays.stream(states)
                .anyMatch(status -> equalsIgnoreCase(status.getValue(), charge.getStatus()));
    }

    private GatewayError captureErrorMessageFor(String currentStatus) {
        return baseGatewayError(format("Cannot capture a charge with status %s.", currentStatus));
    }

    private GatewayError cancelErrorMessageFor(Long chargeId, String status) {
        return baseGatewayError(format("Cannot cancel a charge id [%s]: status is [%s].", chargeId, status));
    }

    private Supplier<Either<GatewayError, GatewayResponse>> chargeNotFound(Long chargeId) {
        return () -> left(new GatewayError(format("Charge with id [%s] not found.", chargeId), CHARGE_NOT_FOUND));
    }
}
