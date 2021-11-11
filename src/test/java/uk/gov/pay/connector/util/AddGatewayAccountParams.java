package uk.gov.pay.connector.util;

import uk.gov.pay.connector.gatewayaccount.model.EmailCollectionMode;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static uk.gov.pay.connector.gateway.PaymentGatewayName.EPDQ;
import static uk.gov.pay.connector.gatewayaccount.model.EmailCollectionMode.MANDATORY;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccount.CREDENTIALS_MERCHANT_ID;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccount.CREDENTIALS_PASSWORD;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccount.CREDENTIALS_SHA_IN_PASSPHRASE;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccount.CREDENTIALS_SHA_OUT_PASSPHRASE;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccount.CREDENTIALS_USERNAME;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccountType.TEST;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialState.ACTIVE;
import static uk.gov.pay.connector.util.AddGatewayAccountCredentialsParams.AddGatewayAccountCredentialsParamsBuilder.anAddGatewayAccountCredentialsParams;
import static uk.gov.pay.connector.util.RandomIdGenerator.randomUuid;

public class AddGatewayAccountParams {
    private static final Map<String, String> defaultCredentials = Map.of(
            CREDENTIALS_MERCHANT_ID, "merchant-id",
            CREDENTIALS_USERNAME, "username",
            CREDENTIALS_PASSWORD, "password"
    );

    private static final Map<String, String> epdqCredentials = Map.of(
            CREDENTIALS_MERCHANT_ID, "merchant-id",
            CREDENTIALS_USERNAME, "username",
            CREDENTIALS_PASSWORD, "password",
            CREDENTIALS_SHA_IN_PASSPHRASE, "sha-in",
            CREDENTIALS_SHA_OUT_PASSPHRASE, "sha-out"
    );

    private String accountId;
    private String externalId;
    private List<AddGatewayAccountCredentialsParams> credentials;
    private String serviceName;
    private String serviceId;
    private GatewayAccountType type;
    private String description;
    private String analyticsId;
    private EmailCollectionMode emailCollectionMode;
    private long corporateCreditCardSurchargeAmount;
    private long corporateDebitCardSurchargeAmount;
    private long corporatePrepaidDebitCardSurchargeAmount;
    private int integrationVersion3ds;
    private boolean allowMoto;
    private boolean motoMaskCardNumberInput;
    private boolean motoMaskCardSecurityCodeInput;
    private boolean allowApplePay;
    private boolean allowGooglePay;
    private boolean requires3ds;
    private boolean allowTelephonePaymentNotifications;
    private boolean providerSwitchEnabled;

    public int getIntegrationVersion3ds() {
        return integrationVersion3ds;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getExternalId() {
        return externalId;
    }

    public List<AddGatewayAccountCredentialsParams> getCredentials() {
        return credentials;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getServiceId() {
        return serviceId;
    }

    public GatewayAccountType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public String getAnalyticsId() {
        return analyticsId;
    }

    public EmailCollectionMode getEmailCollectionMode() {
        return emailCollectionMode;
    }

    public long getCorporateCreditCardSurchargeAmount() {
        return corporateCreditCardSurchargeAmount;
    }

    public long getCorporateDebitCardSurchargeAmount() {
        return corporateDebitCardSurchargeAmount;
    }

    public long getCorporatePrepaidDebitCardSurchargeAmount() {
        return corporatePrepaidDebitCardSurchargeAmount;
    }

    public boolean isAllowMoto() {
        return allowMoto;
    }

    public boolean isMotoMaskCardNumberInput() {
        return motoMaskCardNumberInput;
    }

    public boolean isMotoMaskCardSecurityCodeInput() {
        return motoMaskCardSecurityCodeInput;
    }

    public boolean isAllowApplePay() {
        return allowApplePay;
    }

    public boolean isAllowGooglePay() {
        return allowGooglePay;
    }

    public boolean isRequires3ds() {
        return requires3ds;
    }

    public boolean isAllowTelephonePaymentNotifications() {
        return allowTelephonePaymentNotifications;
    }

    public boolean isProviderSwitchEnabled() {
        return providerSwitchEnabled;
    }

    public static final class AddGatewayAccountParamsBuilder {
        private String accountId;
        private String paymentGateway = "sandbox";
        private Map<String, String> credentialsMap = Map.of();
        private List<AddGatewayAccountCredentialsParams> gatewayAccountCredentialsParams;
        private String serviceName = "service name";
        private String serviceId = "a-valid-service-external-id";
        private GatewayAccountType type = TEST;
        private String description = "description";
        private String analyticsId;
        private EmailCollectionMode emailCollectionMode = MANDATORY;
        private long corporateCreditCardSurchargeAmount;
        private long corporateDebitCardSurchargeAmount;
        private long corporatePrepaidDebitCardSurchargeAmount;
        private int integrationVersion3ds = 2;
        private boolean allowMoto;
        private boolean motoMaskCardNumberInput;
        private boolean motoMaskCardSecurityCodeInput;
        private boolean allowApplePay;
        private boolean allowGooglePay;
        private boolean requires3ds;
        private String externalId = randomUuid();
        private boolean allowTelephonePaymentNotifications;
        private boolean providerSwitchEnabled = false;

        private AddGatewayAccountParamsBuilder() {
        }

        public static AddGatewayAccountParamsBuilder anAddGatewayAccountParams() {
            return new AddGatewayAccountParamsBuilder();
        }

        public AddGatewayAccountParamsBuilder withAccountId(String accountId) {
            this.accountId = accountId;
            return this;
        }

        public AddGatewayAccountParamsBuilder withPaymentGateway(String paymentGateway) {
            this.paymentGateway = paymentGateway;
            return this;
        }

        public AddGatewayAccountParamsBuilder withCredentials(Map<String, String> credentials) {
            this.credentialsMap = credentials;
            return this;
        }

        public AddGatewayAccountParamsBuilder withGatewayAccountCredentials(List<AddGatewayAccountCredentialsParams> credentials) {
            this.gatewayAccountCredentialsParams = credentials;
            return this;
        }

        public AddGatewayAccountParamsBuilder withDefaultCredentials(String providerName) {
            this.gatewayAccountCredentialsParams = List.of(anAddGatewayAccountCredentialsParams()
                    .withCredentials(defaultCredentials)
                    .withState(ACTIVE)
                    .withPaymentProvider(providerName)
                    .withGatewayAccountId(Long.valueOf(this.accountId))
                    .build());
            return this;
        }

        public AddGatewayAccountParamsBuilder withEpdqCredentials() {
            this.gatewayAccountCredentialsParams = List.of(anAddGatewayAccountCredentialsParams()
                    .withCredentials(defaultCredentials)
                    .withState(ACTIVE)
                    .withPaymentProvider(EPDQ.getName())
                    .withGatewayAccountId(Long.valueOf(this.accountId))
                    .build());
            return this;
        }

        public AddGatewayAccountParamsBuilder withServiceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public AddGatewayAccountParamsBuilder withServiceId(String serviceId) {
            this.serviceId = serviceId;
            return this;
        }

        public AddGatewayAccountParamsBuilder withType(GatewayAccountType type) {
            this.type = type;
            return this;
        }

        public AddGatewayAccountParamsBuilder withDescription(String description) {
            this.description = description;
            return this;
        }

        public AddGatewayAccountParamsBuilder withAnalyticsId(String analyticsId) {
            this.analyticsId = analyticsId;
            return this;
        }

        public AddGatewayAccountParamsBuilder withEmailCollectionMode(EmailCollectionMode emailCollectionMode) {
            this.emailCollectionMode = emailCollectionMode;
            return this;
        }

        public AddGatewayAccountParamsBuilder withCorporateCreditCardSurchargeAmount(long corporateCreditCardSurchargeAmount) {
            this.corporateCreditCardSurchargeAmount = corporateCreditCardSurchargeAmount;
            return this;
        }

        public AddGatewayAccountParamsBuilder withCorporateDebitCardSurchargeAmount(long corporateDebitCardSurchargeAmount) {
            this.corporateDebitCardSurchargeAmount = corporateDebitCardSurchargeAmount;
            return this;
        }

        public AddGatewayAccountParamsBuilder withCorporatePrepaidDebitCardSurchargeAmount(long corporatePrepaidDebitCardSurchargeAmount) {
            this.corporatePrepaidDebitCardSurchargeAmount = corporatePrepaidDebitCardSurchargeAmount;
            return this;
        }

        public AddGatewayAccountParamsBuilder withAllowMoto(boolean allowMoto) {
            this.allowMoto = allowMoto;
            return this;
        }

        public AddGatewayAccountParamsBuilder withMotoMaskCardNumberInput(boolean motoMaskCardNumberInput) {
            this.motoMaskCardNumberInput = motoMaskCardNumberInput;
            return this;
        }

        public AddGatewayAccountParamsBuilder withMotoMaskCardSecurityCodeInput(boolean motoMaskCardSecurityCodeInput) {
            this.motoMaskCardSecurityCodeInput = motoMaskCardSecurityCodeInput;
            return this;
        }

        public AddGatewayAccountParamsBuilder withAllowApplePay(boolean allowApplePay) {
            this.allowApplePay = allowApplePay;
            return this;
        }

        public AddGatewayAccountParamsBuilder withAllowGooglePay(boolean allowGooglePay) {
            this.allowGooglePay = allowGooglePay;
            return this;
        }

        public AddGatewayAccountParamsBuilder withRequires3ds(boolean requires3ds) {
            this.requires3ds = requires3ds;
            return this;
        }

        public AddGatewayAccountParamsBuilder withAllowTelephonePaymentNotifications(boolean allowTelephonePaymentNotifications) {
            this.allowTelephonePaymentNotifications = allowTelephonePaymentNotifications;
            return this;
        }

        public AddGatewayAccountParamsBuilder withProviderSwitchEnabled(boolean providerSwitchEnabled) {
            this.providerSwitchEnabled = providerSwitchEnabled;
            return this;
        }

        public AddGatewayAccountParams build() {
            if (gatewayAccountCredentialsParams == null) {
                gatewayAccountCredentialsParams = Collections.singletonList(
                        anAddGatewayAccountCredentialsParams()
                                .withPaymentProvider(paymentGateway)
                                .withGatewayAccountId(Long.parseLong(accountId))
                                .withCredentials(credentialsMap).build());
            }

            AddGatewayAccountParams addGatewayAccountParams = new AddGatewayAccountParams();
            addGatewayAccountParams.accountId = this.accountId;
            addGatewayAccountParams.externalId = this.externalId;
            addGatewayAccountParams.corporatePrepaidDebitCardSurchargeAmount = this.corporatePrepaidDebitCardSurchargeAmount;
            addGatewayAccountParams.analyticsId = this.analyticsId;
            addGatewayAccountParams.type = this.type;
            addGatewayAccountParams.credentials = this.gatewayAccountCredentialsParams;
            addGatewayAccountParams.description = this.description;
            addGatewayAccountParams.serviceName = this.serviceName;
            addGatewayAccountParams.corporateCreditCardSurchargeAmount = this.corporateCreditCardSurchargeAmount;
            addGatewayAccountParams.emailCollectionMode = this.emailCollectionMode;
            addGatewayAccountParams.corporateDebitCardSurchargeAmount = this.corporateDebitCardSurchargeAmount;
            addGatewayAccountParams.integrationVersion3ds = this.integrationVersion3ds;
            addGatewayAccountParams.allowMoto = this.allowMoto;
            addGatewayAccountParams.motoMaskCardNumberInput = this.motoMaskCardNumberInput;
            addGatewayAccountParams.motoMaskCardSecurityCodeInput = this.motoMaskCardSecurityCodeInput;
            addGatewayAccountParams.allowApplePay = this.allowApplePay;
            addGatewayAccountParams.allowGooglePay = this.allowGooglePay;
            addGatewayAccountParams.requires3ds = this.requires3ds;
            addGatewayAccountParams.allowTelephonePaymentNotifications = this.allowTelephonePaymentNotifications;
            addGatewayAccountParams.providerSwitchEnabled = this.providerSwitchEnabled;
            addGatewayAccountParams.serviceId = this.serviceId;
            return addGatewayAccountParams;
        }

        public AddGatewayAccountParamsBuilder withIntegrationVersion3ds(int integrationVersion3ds) {
            this.integrationVersion3ds = integrationVersion3ds;
            return this;
        }

        public AddGatewayAccountParamsBuilder withExternalId(String externalId) {
            this.externalId = externalId;
            return this;
        }
    }
}
