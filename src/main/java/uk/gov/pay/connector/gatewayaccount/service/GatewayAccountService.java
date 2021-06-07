package uk.gov.pay.connector.gatewayaccount.service;

import com.google.inject.persist.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.app.ConnectorConfiguration;
import uk.gov.pay.connector.cardtype.dao.CardTypeDao;
import uk.gov.pay.connector.common.model.api.jsonpatch.JsonPatchRequest;
import uk.gov.pay.connector.gatewayaccount.dao.GatewayAccountDao;
import uk.gov.pay.connector.gatewayaccount.exception.DigitalWalletNotSupportedGatewayException;
import uk.gov.pay.connector.gatewayaccount.exception.MerchantIdWithoutCredentialsException;
import uk.gov.pay.connector.gatewayaccount.exception.MissingWorldpay3dsFlexCredentialsEntityException;
import uk.gov.pay.connector.gatewayaccount.exception.NotSupportedGatewayAccountException;
import uk.gov.pay.connector.gatewayaccount.model.EmailCollectionMode;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccount;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountRequest;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountResourceDTO;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountResponse;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountSearchParams;
import uk.gov.pay.connector.gatewayaccountcredentials.service.GatewayAccountCredentialsService;

import javax.inject.Inject;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.UriInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static com.google.common.collect.Maps.newHashMap;
import static java.util.Map.entry;
import static uk.gov.pay.connector.gateway.PaymentGatewayName.WORLDPAY;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.CREDENTIALS_GATEWAY_MERCHANT_ID;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_ALLOW_APPLE_PAY;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_ALLOW_GOOGLE_PAY;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_ALLOW_MOTO;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_ALLOW_TELEPHONE_PAYMENT_NOTIFICATIONS;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_ALLOW_ZERO_AMOUNT;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_BLOCK_PREPAID_CARDS;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_CORPORATE_CREDIT_CARD_SURCHARGE_AMOUNT;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_CORPORATE_DEBIT_CARD_SURCHARGE_AMOUNT;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_CORPORATE_PREPAID_CREDIT_CARD_SURCHARGE_AMOUNT;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_CORPORATE_PREPAID_DEBIT_CARD_SURCHARGE_AMOUNT;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_EMAIL_COLLECTION_MODE;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_INTEGRATION_VERSION_3DS;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_MOTO_MASK_CARD_NUMBER_INPUT;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_MOTO_MASK_CARD_SECURITY_CODE_INPUT;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_NOTIFY_SETTINGS;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_SEND_PAYER_IP_ADDRESS_TO_GATEWAY;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_WORLDPAY_EXEMPTION_ENGINE_ENABLED;
import static uk.gov.pay.connector.gatewayaccount.resource.GatewayAccountRequestValidator.FIELD_PROVIDER_SWITCH_ENABLED;

public class GatewayAccountService {

    private static final Logger logger = LoggerFactory.getLogger(GatewayAccountService.class);

    private final GatewayAccountDao gatewayAccountDao;
    private final CardTypeDao cardTypeDao;
    private final GatewayAccountCredentialsService gatewayAccountCredentialsService;

    @Inject
    public GatewayAccountService(GatewayAccountDao gatewayAccountDao, CardTypeDao cardTypeDao,
                                 GatewayAccountCredentialsService gatewayAccountCredentialsService) {
        this.gatewayAccountDao = gatewayAccountDao;
        this.cardTypeDao = cardTypeDao;
        this.gatewayAccountCredentialsService = gatewayAccountCredentialsService;
    }

    public Optional<GatewayAccountEntity> getGatewayAccount(long gatewayAccountId) {
        return gatewayAccountDao.findById(gatewayAccountId);
    }

    public List<GatewayAccountResourceDTO> searchGatewayAccounts(GatewayAccountSearchParams params) {
        return gatewayAccountDao.search(params).stream()
                .map(GatewayAccountResourceDTO::new)
                .collect(Collectors.toList());
    }

    @Transactional
    public Optional<GatewayAccount> doPatch(Long gatewayAccountId, JsonPatchRequest gatewayAccountRequest) {
        return gatewayAccountDao.findById(gatewayAccountId)
                .flatMap(gatewayAccountEntity -> {
                    attributeUpdater.get(gatewayAccountRequest.getPath())
                            .accept(gatewayAccountRequest, gatewayAccountEntity);
                    gatewayAccountDao.merge(gatewayAccountEntity);
                    return Optional.of(GatewayAccount.valueOf(gatewayAccountEntity));
                });
    }

    @Transactional
    public GatewayAccountResponse createGatewayAccount(GatewayAccountRequest gatewayAccountRequest, UriInfo uriInfo) {

        GatewayAccountEntity gatewayAccountEntity = GatewayAccountObjectConverter.createEntityFrom(gatewayAccountRequest);

        logger.info("Setting the new account to accept all card types by default");

        gatewayAccountEntity.setCardTypes(cardTypeDao.findAllNon3ds());

        gatewayAccountDao.persist(gatewayAccountEntity);

        gatewayAccountCredentialsService.createGatewayAccountCredentials(gatewayAccountEntity,
                gatewayAccountRequest.getPaymentProvider(), gatewayAccountRequest.getCredentialsAsMap());

        return GatewayAccountObjectConverter.createResponseFrom(gatewayAccountEntity, uriInfo);
    }

    public Optional<GatewayAccountEntity> getGatewayAccountByExternal(String gatewayAccountExternalId) {
        return gatewayAccountDao.findByExternalId(gatewayAccountExternalId);
    }

    public boolean isATelephonePaymentNotificationAccount(String merchantCode) {
        return gatewayAccountDao.isATelephonePaymentNotificationAccount(merchantCode);
    }

    private final Map<String, BiConsumer<JsonPatchRequest, GatewayAccountEntity>> attributeUpdater = Map.ofEntries(
            entry(
                CREDENTIALS_GATEWAY_MERCHANT_ID,
                (gatewayAccountRequest, gatewayAccountEntity) -> {
                    Map<String, String> credentials = gatewayAccountEntity.getCredentials();
                    if (credentials.isEmpty()) {
                        throw new MerchantIdWithoutCredentialsException();
                    }
                    throwIfNotDigitalWalletSupportedGateway(gatewayAccountEntity);
                    Map<String, String> updatedCredentials = new HashMap<>(credentials);
                    updatedCredentials.put("gateway_merchant_id", gatewayAccountRequest.valueAsString());
                    gatewayAccountEntity.setCredentials(updatedCredentials);
                }
            ),
            entry(
                FIELD_ALLOW_GOOGLE_PAY,
                (gatewayAccountRequest, gatewayAccountEntity) -> {
                    throwIfNotDigitalWalletSupportedGateway(gatewayAccountEntity);
                    gatewayAccountEntity.setAllowGooglePay(Boolean.parseBoolean(gatewayAccountRequest.valueAsString()));
                }
            ),
            entry(
                FIELD_ALLOW_APPLE_PAY,
                (gatewayAccountRequest, gatewayAccountEntity) -> {
                    throwIfNotDigitalWalletSupportedGateway(gatewayAccountEntity);
                    gatewayAccountEntity.setAllowApplePay(Boolean.parseBoolean(gatewayAccountRequest.valueAsString()));
                }
            ),
            entry(
                FIELD_NOTIFY_SETTINGS,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setNotifySettings(gatewayAccountRequest.valueAsObject())
            ),
            entry(
                FIELD_EMAIL_COLLECTION_MODE,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setEmailCollectionMode(EmailCollectionMode.fromString(gatewayAccountRequest.valueAsString()))
            ),
            entry(
                FIELD_CORPORATE_CREDIT_CARD_SURCHARGE_AMOUNT,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setCorporateCreditCardSurchargeAmount(gatewayAccountRequest.valueAsLong())
            ),
            entry(
                FIELD_CORPORATE_DEBIT_CARD_SURCHARGE_AMOUNT,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setCorporateDebitCardSurchargeAmount(gatewayAccountRequest.valueAsLong())
            ),
            entry(
                FIELD_CORPORATE_PREPAID_CREDIT_CARD_SURCHARGE_AMOUNT,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setCorporatePrepaidCreditCardSurchargeAmount(gatewayAccountRequest.valueAsLong())
            ),
            entry(
                FIELD_CORPORATE_PREPAID_DEBIT_CARD_SURCHARGE_AMOUNT,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setCorporatePrepaidDebitCardSurchargeAmount(gatewayAccountRequest.valueAsLong())
            ),
            entry(
                FIELD_ALLOW_ZERO_AMOUNT,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setAllowZeroAmount(Boolean.parseBoolean(gatewayAccountRequest.valueAsString()))
            ),
            entry(
                FIELD_INTEGRATION_VERSION_3DS,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setIntegrationVersion3ds(gatewayAccountRequest.valueAsInt())
            ),
            entry(
                FIELD_BLOCK_PREPAID_CARDS,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setBlockPrepaidCards(gatewayAccountRequest.valueAsBoolean())
            ),
            entry(
                FIELD_ALLOW_MOTO,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setAllowMoto(gatewayAccountRequest.valueAsBoolean())
            ),
            entry(
                FIELD_MOTO_MASK_CARD_NUMBER_INPUT,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setMotoMaskCardNumberInput(gatewayAccountRequest.valueAsBoolean())
            ),
            entry(
                FIELD_MOTO_MASK_CARD_SECURITY_CODE_INPUT,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setMotoMaskCardSecurityCodeInput(gatewayAccountRequest.valueAsBoolean())
            ),
            entry(
                FIELD_ALLOW_TELEPHONE_PAYMENT_NOTIFICATIONS,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setAllowTelephonePaymentNotifications(gatewayAccountRequest.valueAsBoolean())
            ),
            entry(
                FIELD_SEND_PAYER_IP_ADDRESS_TO_GATEWAY,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setSendPayerIpAddressToGateway(gatewayAccountRequest.valueAsBoolean())
            ),
            entry(
                FIELD_WORLDPAY_EXEMPTION_ENGINE_ENABLED,
                (gatewayAccountRequest, gatewayAccountEntity) -> {
                    throwIfGatewayAccountIsNotWorldpay(gatewayAccountEntity, FIELD_WORLDPAY_EXEMPTION_ENGINE_ENABLED);
                    var worldpay3dsFlexCredentialsEntity = gatewayAccountEntity.getWorldpay3dsFlexCredentialsEntity()
                        .orElseThrow(() -> new MissingWorldpay3dsFlexCredentialsEntityException(gatewayAccountEntity.getId(), FIELD_WORLDPAY_EXEMPTION_ENGINE_ENABLED));
                    worldpay3dsFlexCredentialsEntity.setExemptionEngineEnabled(gatewayAccountRequest.valueAsBoolean());
                }
            ),
            entry(
                FIELD_PROVIDER_SWITCH_ENABLED,
                (gatewayAccountRequest, gatewayAccountEntity) -> gatewayAccountEntity.setProviderSwitchEnabled(gatewayAccountRequest.valueAsBoolean())
            )
    );

    private void throwIfNotDigitalWalletSupportedGateway(GatewayAccountEntity gatewayAccountEntity) {
        if (!WORLDPAY.getName().equals(gatewayAccountEntity.getGatewayName())) {
            throw new DigitalWalletNotSupportedGatewayException(gatewayAccountEntity.getGatewayName());
        }
    }

    private void throwIfGatewayAccountIsNotWorldpay(GatewayAccountEntity gatewayAccountEntity, String path) {
        if (!WORLDPAY.getName().equals(gatewayAccountEntity.getGatewayName())) {
            throw new NotSupportedGatewayAccountException(gatewayAccountEntity.getId(), WORLDPAY.getName(), path);
        }
    }
}
