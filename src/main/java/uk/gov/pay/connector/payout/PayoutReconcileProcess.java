package uk.gov.pay.connector.payout;

import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.Payout;
import com.stripe.model.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.app.ConnectorConfiguration;
import uk.gov.pay.connector.app.StripeGatewayConfig;
import uk.gov.pay.connector.events.EventService;
import uk.gov.pay.connector.events.model.Event;
import uk.gov.pay.connector.events.model.charge.PaymentIncludedInPayout;
import uk.gov.pay.connector.events.model.payout.PayoutCreated;
import uk.gov.pay.connector.events.model.payout.PayoutEvent;
import uk.gov.pay.connector.events.model.refund.RefundIncludedInPayout;
import uk.gov.pay.connector.gateway.stripe.json.StripePayout;
import uk.gov.pay.connector.gateway.stripe.json.StripePayoutStatus;
import uk.gov.pay.connector.gateway.stripe.request.StripeTransferMetadata;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.gatewayaccount.model.StripeCredentials;
import uk.gov.pay.connector.gatewayaccountcredentials.service.GatewayAccountCredentialsService;
import uk.gov.pay.connector.queue.payout.PayoutReconcileMessage;
import uk.gov.pay.connector.queue.payout.PayoutReconcileQueue;
import uk.gov.service.payments.commons.queue.exception.QueueException;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Boolean.TRUE;
import static java.lang.String.format;
import static net.logstash.logback.argument.StructuredArguments.kv;
import static uk.gov.pay.connector.gateway.stripe.request.StripeTransferMetadataReason.TRANSFER_FEE_AMOUNT_FOR_FAILED_PAYMENT;
import static uk.gov.service.payments.logging.LoggingKeys.CONNECT_ACCOUNT_ID;
import static uk.gov.service.payments.logging.LoggingKeys.GATEWAY_PAYOUT_ID;
import static uk.gov.service.payments.logging.LoggingKeys.PAYMENT_EXTERNAL_ID;
import static uk.gov.service.payments.logging.LoggingKeys.REFUND_EXTERNAL_ID;

public class PayoutReconcileProcess {

    private static final Logger LOGGER = LoggerFactory.getLogger(PayoutReconcileProcess.class);
    private final PayoutReconcileQueue payoutReconcileQueue;
    private final StripeClientWrapper stripeClientWrapper;
    private final StripeGatewayConfig stripeGatewayConfig;
    private final ConnectorConfiguration connectorConfiguration;
    private final GatewayAccountCredentialsService gatewayAccountCredentialsService;
    private final EventService eventService;
    private final PayoutEmitterService payoutEmitterService;

    @Inject
    public PayoutReconcileProcess(PayoutReconcileQueue payoutReconcileQueue,
                                  StripeClientWrapper stripeClientWrapper,
                                  StripeGatewayConfig stripeGatewayConfig,
                                  ConnectorConfiguration connectorConfiguration,
                                  GatewayAccountCredentialsService gatewayAccountCredentialsService,
                                  EventService eventService,
                                  PayoutEmitterService payoutEmitterService) {
        this.payoutReconcileQueue = payoutReconcileQueue;
        this.stripeClientWrapper = stripeClientWrapper;
        this.stripeGatewayConfig = stripeGatewayConfig;
        this.connectorConfiguration = connectorConfiguration;
        this.gatewayAccountCredentialsService = gatewayAccountCredentialsService;
        this.eventService = eventService;
        this.payoutEmitterService = payoutEmitterService;
    }

    public void processPayouts() throws QueueException {
        List<PayoutReconcileMessage> payoutReconcileMessages = payoutReconcileQueue.retrievePayoutMessages();
        for (PayoutReconcileMessage payoutReconcileMessage : payoutReconcileMessages) {
            try {
                LOGGER.info("Processing payout [{}] for connect account [{}]",
                        payoutReconcileMessage.getGatewayPayoutId(),
                        payoutReconcileMessage.getConnectAccountId());

                String apiKey = getStripeApiKey(payoutReconcileMessage.getConnectAccountId());

                AtomicInteger payments = new AtomicInteger();
                AtomicInteger transfers = new AtomicInteger();

                stripeClientWrapper.getBalanceTransactionsForPayout(payoutReconcileMessage.getGatewayPayoutId(), payoutReconcileMessage.getConnectAccountId(), apiKey)
                        .forEach(balanceTransaction -> {
                            switch (balanceTransaction.getType()) {
                                case "payment":
                                    reconcilePayment(payoutReconcileMessage, balanceTransaction);
                                    payments.getAndIncrement();
                                    break;
                                case "transfer":
                                    reconcileTransfer(payoutReconcileMessage, balanceTransaction);
                                    transfers.getAndIncrement();
                                    break;
                                case "payout":
                                    emitPayoutCreatedEvent(payoutReconcileMessage, balanceTransaction);
                                    break;
                                default:
                                    LOGGER.error(format("Payout contains balance transfer of type [%s], which is unexpected.",
                                            balanceTransaction.getType()),
                                            kv(CONNECT_ACCOUNT_ID, payoutReconcileMessage.getConnectAccountId()),
                                            kv(GATEWAY_PAYOUT_ID, payoutReconcileMessage.getGatewayPayoutId()));
                                    break;
                            }
                        });

                if (payments.intValue() == 0 && transfers.intValue() == 0) {
                    LOGGER.error(format("No payments or refunds retrieved for payout [%s]. Requires investigation.",
                            payoutReconcileMessage.getGatewayPayoutId()),
                            kv(CONNECT_ACCOUNT_ID, payoutReconcileMessage.getConnectAccountId()),
                            kv(GATEWAY_PAYOUT_ID, payoutReconcileMessage.getGatewayPayoutId()));
                } else {
                    LOGGER.info(format("Finished processing payout [%s]. Emitted events for %s payments and %s transfers.",
                            payoutReconcileMessage.getGatewayPayoutId(),
                            payments.intValue(),
                            transfers.intValue()),
                            kv(CONNECT_ACCOUNT_ID, payoutReconcileMessage.getConnectAccountId()),
                            kv(GATEWAY_PAYOUT_ID, payoutReconcileMessage.getGatewayPayoutId()));

                    payoutReconcileQueue.markMessageAsProcessed(payoutReconcileMessage.getQueueMessage());
                }
            } catch (Exception e) {
                LOGGER.error(format("Error processing payout from SQS message [queueMessageId=%s] [errorMessage=%s]",
                        payoutReconcileMessage.getQueueMessageId(),
                        e.getMessage()),
                        kv(CONNECT_ACCOUNT_ID, payoutReconcileMessage.getConnectAccountId()),
                        kv(GATEWAY_PAYOUT_ID, payoutReconcileMessage.getGatewayPayoutId()));
            }
        }
    }

    private void emitPayoutCreatedEvent(PayoutReconcileMessage payoutReconcileMessage, BalanceTransaction balanceTransaction) {
        Payout payoutObject = (Payout) balanceTransaction.getSourceObject();
        StripePayout stripePayout = StripePayout.from(payoutObject);

        payoutEmitterService.emitPayoutEvent(PayoutCreated.class, stripePayout.getCreated(),
                payoutReconcileMessage.getConnectAccountId(), stripePayout);

        emitTerminalPayoutEvent(payoutReconcileMessage.getConnectAccountId(), stripePayout);
    }

    /**
     * Payout events (except PAYOUT_CREATED) are usually emitted to event queue from StripeNotificationService
     * as soon as a notification is received.
     * This method will provide a mechanism (send payout info to reconcile queue) to emit payout events which are not
     * emitted from StripeNotificationService for reasons out of control, for example, event queue not available.
     */
    private void emitTerminalPayoutEvent(String connectAccountId, StripePayout stripePayout) {
        StripePayoutStatus stripePayoutStatus = StripePayoutStatus.fromString(stripePayout.getStatus());
        if (stripePayoutStatus.isTerminal()) {
            Optional<Class<? extends PayoutEvent>> mayBeEventClass = stripePayoutStatus.getEventClass();
            mayBeEventClass.ifPresentOrElse(
                    eventClass -> payoutEmitterService.emitPayoutEvent(eventClass, stripePayout.getCreated(),
                            connectAccountId, stripePayout),
                    () -> LOGGER.warn("Event class is not available for a payout in terminal status. " +
                                    "gateway_payout_id [{}], connect_account_id [{}], status [{}]",
                            stripePayout.getId(), connectAccountId, stripePayout.getStatus())
            );
        }
    }

    private String getStripeApiKey(String stripeAccountId) {
        GatewayAccountEntity gatewayAccountEntity = gatewayAccountCredentialsService
                .findStripeGatewayAccountForCredentialKeyAndValue(StripeCredentials.STRIPE_ACCOUNT_ID_KEY, stripeAccountId);

         return gatewayAccountEntity.isLive() ? stripeGatewayConfig.getAuthTokens().getLive() : stripeGatewayConfig.getAuthTokens().getTest();
    }

    private void reconcilePayment(PayoutReconcileMessage payoutReconcileMessage, BalanceTransaction balanceTransaction) {
        var paymentSource = (Charge) balanceTransaction.getSourceObject();
        var paymentSourceTransfer = paymentSource.getSourceTransferObject();
        StripeTransferMetadata stripeTransferMetadata = getStripeTransferMetadata(paymentSourceTransfer);
        String paymentExternalId = resolveTransactionExternalId(payoutReconcileMessage, balanceTransaction, stripeTransferMetadata);

        emitPaymentEvent(payoutReconcileMessage, paymentExternalId);
    }

    private void reconcileTransfer(PayoutReconcileMessage payoutReconcileMessage, BalanceTransaction balanceTransaction) {
        var sourceTransfer = (Transfer) balanceTransaction.getSourceObject();
        var stripeTransferMetadata = getStripeTransferMetadata(sourceTransfer);
        String transactionExternalId = resolveTransactionExternalId(payoutReconcileMessage, balanceTransaction, stripeTransferMetadata);
        if (stripeTransferMetadata.getReason() == TRANSFER_FEE_AMOUNT_FOR_FAILED_PAYMENT) {
            emitPaymentEvent(payoutReconcileMessage, transactionExternalId);
        } else {
            emitRefundEvent(payoutReconcileMessage, transactionExternalId);
        }
    }

    private StripeTransferMetadata getStripeTransferMetadata(Transfer sourceTransfer) {
        return StripeTransferMetadata.from(sourceTransfer.getMetadata());
    }

    private void emitPaymentEvent(PayoutReconcileMessage payoutReconcileMessage, String paymentExternalId) {
        var paymentEvent = new PaymentIncludedInPayout(paymentExternalId,
                payoutReconcileMessage.getGatewayPayoutId(),
                payoutReconcileMessage.getCreatedDate());
        emitEvent(paymentEvent, payoutReconcileMessage, paymentExternalId);

        LOGGER.info(format("Emitted event for payment [%s] included in payout [%s]",
                        paymentExternalId,
                        payoutReconcileMessage.getGatewayPayoutId()),
                kv(PAYMENT_EXTERNAL_ID, paymentExternalId),
                kv(CONNECT_ACCOUNT_ID, payoutReconcileMessage.getConnectAccountId()),
                kv(GATEWAY_PAYOUT_ID, payoutReconcileMessage.getGatewayPayoutId()));
    }

    private void emitRefundEvent(PayoutReconcileMessage payoutReconcileMessage, String refundExternalId) {
        var refundEvent = new RefundIncludedInPayout(refundExternalId,
                payoutReconcileMessage.getGatewayPayoutId(),
                payoutReconcileMessage.getCreatedDate());
        emitEvent(refundEvent, payoutReconcileMessage, refundExternalId);

        LOGGER.info(format("Emitted event for refund [%s] included in payout [%s]",
                refundExternalId,
                payoutReconcileMessage.getGatewayPayoutId()),
                kv(REFUND_EXTERNAL_ID, refundExternalId),
                kv(CONNECT_ACCOUNT_ID, payoutReconcileMessage.getConnectAccountId()),
                kv(GATEWAY_PAYOUT_ID, payoutReconcileMessage.getGatewayPayoutId()));
    }

    private String resolveTransactionExternalId(PayoutReconcileMessage payoutReconcileMessage, BalanceTransaction balanceTransaction, StripeTransferMetadata stripeTransferMetadata) {
        var transactionExternalId = stripeTransferMetadata.getGovukPayTransactionExternalId();
        if (transactionExternalId == null) {
            throw new RuntimeException(format("Transaction external ID missing in metadata on Stripe transfer for " +
                            "balance transaction [%s] for payout [%s] for gateway account [%s]",
                    balanceTransaction.getId(), payoutReconcileMessage.getGatewayPayoutId(),
                    payoutReconcileMessage.getConnectAccountId()));
        }
        return transactionExternalId;
    }

    private void emitEvent(Event event, PayoutReconcileMessage payoutReconcileMessage, String transactionExternalId) {
        if (TRUE.equals(connectorConfiguration.getEmitPayoutEvents())) {
            try {
                eventService.emitEvent(event, false);
            } catch (QueueException e) {
                throw new RuntimeException(format("Error sending %s event for transaction [%s] included in payout [%s] to event queue: %s",
                        event.getEventType(), transactionExternalId, payoutReconcileMessage.getGatewayPayoutId(), e.getMessage()), e);
            }
        }
    }
}
