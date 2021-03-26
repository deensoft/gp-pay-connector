package uk.gov.pay.connector.usernotification.service;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Stopwatch;
import io.dropwizard.setup.Environment;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import uk.gov.pay.connector.app.ConnectorConfiguration;
import uk.gov.pay.connector.charge.model.domain.Charge;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.util.CorporateCardSurchargeCalculator;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.refund.model.domain.RefundEntity;
import uk.gov.pay.connector.usernotification.govuknotify.NotifyClientFactory;
import uk.gov.pay.connector.usernotification.model.domain.EmailNotificationEntity;
import uk.gov.pay.connector.usernotification.model.domain.EmailNotificationType;
import uk.gov.pay.connector.util.DateTimeUtils;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.NotificationClientException;
import uk.gov.service.notify.SendEmailResponse;

import javax.inject.Inject;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Runtime.getRuntime;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static net.logstash.logback.argument.StructuredArguments.kv;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static uk.gov.pay.connector.gatewayaccount.model.EmailCollectionMode.OFF;
import static uk.gov.pay.connector.gatewayaccount.model.EmailCollectionMode.OPTIONAL;
import static uk.gov.pay.logging.LoggingKeys.GATEWAY_ACCOUNT_ID;
import static uk.gov.pay.logging.LoggingKeys.PAYMENT_EXTERNAL_ID;


public class UserNotificationService {

    private static final Pattern LITERAL_DOLLAR_REFERENCE = Pattern.compile(Pattern.quote("$reference"));

    private String confirmationEmailTemplateId;
    private String refundIssuedEmailTemplateId;
    private boolean emailNotifyGloballyEnabled;
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private NotifyClientFactory notifyClientFactory;
    private ExecutorService executorService;
    private final MetricRegistry metricRegistry;

    @Inject
    public UserNotificationService(NotifyClientFactory notifyClientFactory, ConnectorConfiguration configuration, Environment environment) {
        readEmailConfig(configuration);
        if (emailNotifyGloballyEnabled) {
            this.notifyClientFactory = notifyClientFactory;
            int numberOfThreads = configuration.getExecutorServiceConfig().getThreadsPerCpu() * getRuntime().availableProcessors();
            executorService = Executors.newFixedThreadPool(numberOfThreads);
        }
        this.metricRegistry = environment.metrics();
    }

    public Future<Optional<String>> sendRefundIssuedEmail(RefundEntity refundEntity, Charge charge, GatewayAccountEntity gatewayAccountEntity) {
        return sendEmail(EmailNotificationType.REFUND_ISSUED, charge, gatewayAccountEntity,
                buildRefundEmailPersonalisationFrom(charge, refundEntity, gatewayAccountEntity));
    }

    public Future<Optional<String>> sendPaymentConfirmedEmail(ChargeEntity chargeEntity, GatewayAccountEntity gatewayAccountEntity) {
        return sendEmail(EmailNotificationType.PAYMENT_CONFIRMED, Charge.from(chargeEntity), gatewayAccountEntity,
                buildConfirmationEmailPersonalisationFrom(chargeEntity));
    }

    private Future<Optional<String>> sendEmail(EmailNotificationType emailNotificationType, Charge charge, GatewayAccountEntity gatewayAccountEntity, HashMap<String, String> personalisation) {
        boolean isEmailEnabled = ofNullable(gatewayAccountEntity.getEmailNotifications().get(emailNotificationType))
                .map(EmailNotificationEntity::isEnabled)
                .orElse(false);
        
        if (!emailNotifyGloballyEnabled || !isEmailEnabled || gatewayAccountEntity.getEmailCollectionMode().equals(OFF) ||
                gatewayAccountEntity.getEmailCollectionMode().equals(OPTIONAL) && ofNullable(charge.getEmail()).isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        if (charge.getEmail() == null) {
            logger.warn("Cannot send email for charge_external_id = {} because the charge does not have an email address", charge.getExternalId());
            return CompletableFuture.completedFuture(Optional.empty());
        }

        Stopwatch responseTimeStopwatch = Stopwatch.createStarted();
        return executorService.submit(() -> {
            try {
                MDC.put(GATEWAY_ACCOUNT_ID, gatewayAccountEntity.getId().toString());
                MDC.put(PAYMENT_EXTERNAL_ID, charge.getExternalId());
                NotifyClientSettings notifyClientSettings = getNotifyClientSettings(emailNotificationType, gatewayAccountEntity);
                logger.info(format("Sending %s email.", emailNotificationType));
                SendEmailResponse response = notifyClientSettings.getClient()
                        .sendEmail(notifyClientSettings.getTemplateId(), charge.getEmail(), personalisation, null);
                return Optional.of(response.getNotificationId().toString());
            } catch (NotificationClientException e) {
                logger.error("Failed to send " + emailNotificationType + " email - charge_external_id=" + charge.getExternalId(), e);
                metricRegistry.counter("notify-operations.failures").inc();
                return Optional.empty();
            } finally {
                MDC.remove(GATEWAY_ACCOUNT_ID);
                MDC.remove(PAYMENT_EXTERNAL_ID);
                responseTimeStopwatch.stop();
                metricRegistry.histogram("notify-operations.response_time").update(responseTimeStopwatch.elapsed(TimeUnit.MILLISECONDS));
            }
        });
    }

    private NotifyClientSettings getNotifyClientSettings(EmailNotificationType emailNotificationType, GatewayAccountEntity gatewayAccountEntity) {
        // todo introduce type for notify settings instead of Map
        Map<String, String> notifySettings = gatewayAccountEntity.getNotifySettings();
        switch (emailNotificationType) {
            case REFUND_ISSUED:
                return NotifyClientSettings.of(notifySettings, notifyClientFactory, "refund_issued_template_id", refundIssuedEmailTemplateId);
            case PAYMENT_CONFIRMED:
                return NotifyClientSettings.of(notifySettings, notifyClientFactory, "template_id", confirmationEmailTemplateId);
        }
        return null;
    }

    private static class NotifyClientSettings {
        private NotificationClient client;
        private String templateId;

        private NotifyClientSettings(NotificationClient client, String templateId) {
            this.client = client;
            this.templateId = templateId;
        }

        public NotificationClient getClient() {
            return client;
        }

        String getTemplateId() {
            return templateId;
        }

        public static NotifyClientSettings of(Map<String, String> notifySettings, NotifyClientFactory notifyClientFactory, String customTemplateId, String payTemplateId) {
            if (hasCustomTemplateAndApiKey(notifySettings, customTemplateId)) {
                return new NotifyClientSettings(notifyClientFactory.getInstance(notifySettings.get("api_token")), notifySettings.get(customTemplateId));
            }
            return new NotifyClientSettings(notifyClientFactory.getInstance(), payTemplateId);
        }
    }
    
    private static boolean hasCustomTemplateAndApiKey(Map<String, String> notifySettings, String customTemplateId) {
        return notifySettings != null  && (notifySettings.containsKey(customTemplateId) && isNotBlank(notifySettings.get("api_token")));
    }


    private void readEmailConfig(ConnectorConfiguration configuration) {
        emailNotifyGloballyEnabled = configuration.getNotifyConfiguration().isEmailNotifyEnabled();
        confirmationEmailTemplateId = configuration.getNotifyConfiguration().getEmailTemplateId();
        refundIssuedEmailTemplateId = configuration.getNotifyConfiguration().getRefundIssuedEmailTemplateId();

        if (!emailNotifyGloballyEnabled) {
            logger.warn("Email notifications is disabled by configuration");
        }
        if (emailNotifyGloballyEnabled && (isBlank(confirmationEmailTemplateId) || isBlank(refundIssuedEmailTemplateId))) {
            throw new RuntimeException("Check notify config, need to set 'emailTemplateId' (payment confirmation email) and 'refundIssuedEmailTemplateId' properties");
        }
    }

    private HashMap<String, String> buildConfirmationEmailPersonalisationFrom(ChargeEntity charge) {
        GatewayAccountEntity gatewayAccount = charge.getGatewayAccount();
        EmailNotificationEntity emailNotification = gatewayAccount
                .getEmailNotifications().get(EmailNotificationType.PAYMENT_CONFIRMED);

        String customParagraph = emailNotification != null ? emailNotification.getTemplateBody() : "";
        HashMap<String, String> map = new HashMap<>();

        map.put("serviceReference", charge.getReference().toString());
        map.put("date", DateTimeUtils.toUserFriendlyDate(charge.getCreatedDate()));
        map.put("amount", formatToPounds(CorporateCardSurchargeCalculator.getTotalAmountFor(charge)));
        map.put("description", charge.getDescription());
        map.put("customParagraph", isBlank(customParagraph) ? "" : "^ " + LITERAL_DOLLAR_REFERENCE.matcher(customParagraph)
                .replaceAll(Matcher.quoteReplacement(charge.getReference().toString())));
        map.put("serviceName", StringUtils.defaultString(gatewayAccount.getServiceName()));
        
        String corporateSurchargeMsg = charge.getCorporateSurcharge()
                .map(corporateSurcharge -> 
                        format("Your payment includes a fee of £%s for using a corporate credit or debit card.",
                                formatToPounds(corporateSurcharge)))
                .orElse("");
        map.put("corporateCardSurcharge", corporateSurchargeMsg);
        
        return map;
    }

    private HashMap<String, String> buildRefundEmailPersonalisationFrom(Charge charge, RefundEntity refundEntity, GatewayAccountEntity gatewayAccountEntity) {
        HashMap<String, String> map = new HashMap<>();

        map.put("serviceReference", charge.getReference());
        map.put("date", DateTimeUtils.toUserFriendlyDate(charge.getCreatedDate()));
        map.put("amount", formatToPounds(refundEntity.getAmount()));
        map.put("description", charge.getDescription());
        map.put("serviceName", StringUtils.defaultString(gatewayAccountEntity.getServiceName()));

        return map;
    }

    private String formatToPounds(long amountInPence) {
        BigDecimal amountInPounds = BigDecimal.valueOf(amountInPence, 2);

        return amountInPounds.toString();
    }
}
