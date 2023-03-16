package uk.gov.pay.connector.charge.service;

import com.google.inject.persist.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.agreement.dao.AgreementDao;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.client.ledger.service.LedgerService;
import uk.gov.pay.connector.events.model.agreement.AgreementSetUp;
import uk.gov.pay.connector.events.model.charge.PaymentInstrumentConfirmed;
import uk.gov.pay.connector.paymentinstrument.model.PaymentInstrumentStatus;

import javax.inject.Inject;
import java.time.Clock;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static uk.gov.service.payments.logging.LoggingKeys.PAYMENT_INSTRUMENT_EXTERNAL_ID;

public class LinkPaymentInstrumentToAgreementService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LinkPaymentInstrumentToAgreementService.class);

    private final AgreementDao agreementDao;
    private final LedgerService ledgerService;
    private final Clock clock;

    @Inject
    public LinkPaymentInstrumentToAgreementService(AgreementDao agreementDao, LedgerService ledgerService, Clock clock) {
        this.agreementDao = agreementDao;
        this.ledgerService = ledgerService;
        this.clock = clock;
    }

    @Transactional
    public void linkPaymentInstrumentFromChargeToAgreement(ChargeEntity chargeEntity) {
        chargeEntity.getPaymentInstrument().ifPresentOrElse(paymentInstrumentEntity -> {
            chargeEntity.getAgreement().ifPresentOrElse(agreementEntity -> {
                agreementEntity.setPaymentInstrument(paymentInstrumentEntity);
                paymentInstrumentEntity.setAgreementExternalId(agreementEntity.getExternalId());
                paymentInstrumentEntity.setStatus(PaymentInstrumentStatus.ACTIVE);
                ledgerService.postEvent(List.of(
                        AgreementSetUp.from(agreementEntity, clock.instant()),
                        PaymentInstrumentConfirmed.from(agreementEntity, clock.instant())
                ));
                LOGGER.info("Agreement successfully set up with payment instrument",
                        kv(PAYMENT_INSTRUMENT_EXTERNAL_ID, paymentInstrumentEntity.getExternalId()));
            }, () -> LOGGER.error("Expected charge {} to have an agreement but it does not have one", chargeEntity.getExternalId()));
        }, () -> LOGGER.error("Expected charge {} to have a payment instrument but it does not have one", chargeEntity.getExternalId()));
    }

}
