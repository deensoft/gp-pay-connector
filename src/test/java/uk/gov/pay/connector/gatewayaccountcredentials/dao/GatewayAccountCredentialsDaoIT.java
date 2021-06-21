package uk.gov.pay.connector.gatewayaccountcredentials.dao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.gov.pay.connector.gatewayaccount.dao.GatewayAccountDao;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialsEntity;
import uk.gov.pay.connector.it.dao.DaoITestBase;
import uk.gov.pay.connector.util.AddGatewayAccountCredentialsParams;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.apache.commons.lang3.RandomUtils.nextLong;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialState.ACTIVE;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialState.CREATED;
import static uk.gov.pay.connector.util.AddGatewayAccountCredentialsParams.AddGatewayAccountCredentialsParamsBuilder.anAddGatewayAccountCredentialsParams;
import static uk.gov.pay.connector.util.AddGatewayAccountParams.AddGatewayAccountParamsBuilder.anAddGatewayAccountParams;
import static uk.gov.pay.connector.util.RandomIdGenerator.randomUuid;

public class GatewayAccountCredentialsDaoIT extends DaoITestBase {
    private GatewayAccountCredentialsDao gatewayAccountCredentialsDao;
    private GatewayAccountDao gatewayAccountDao;

    @Before
    public void setUp() {
        gatewayAccountCredentialsDao = env.getInstance(GatewayAccountCredentialsDao.class);
        gatewayAccountDao = env.getInstance(GatewayAccountDao.class);
    }

    @After
    public void truncate() {
        databaseTestHelper.truncateAllData();
    }

    @Test
    public void hasActiveCredentialsShouldReturnTrueIfGatewayAccountHasActiveCredentials() {
        long gatewayAccountId = nextLong();
        databaseTestHelper.addGatewayAccount(anAddGatewayAccountParams()
                .withAccountId(String.valueOf(gatewayAccountId))
                .withPaymentGateway("stripe")
                .withServiceName("a cool service")
                .build());
        GatewayAccountEntity gatewayAccountEntity = gatewayAccountDao.findById(gatewayAccountId).get();
        GatewayAccountCredentialsEntity gatewayAccountCredentialsEntity
                = new GatewayAccountCredentialsEntity(gatewayAccountEntity, "stripe", Map.of(), ACTIVE);
        gatewayAccountCredentialsEntity.setExternalId(randomUuid());
        gatewayAccountCredentialsDao.persist(gatewayAccountCredentialsEntity);

        boolean result = gatewayAccountCredentialsDao.hasActiveCredentials(gatewayAccountId);

        assertThat(result, is(true));
    }

    @Test
    public void hasActiveCredentialsShouldReturnFalseIfGatewayAccountHasNoActiveCredentials() {
        long gatewayAccountId = nextLong();
        AddGatewayAccountCredentialsParams credentialsParams = anAddGatewayAccountCredentialsParams()
                .withState(CREATED)
                .withPaymentProvider("stripe")
                .withGatewayAccountId(gatewayAccountId)
                .build();
        databaseTestHelper.addGatewayAccount(anAddGatewayAccountParams()
                .withAccountId(String.valueOf(gatewayAccountId))
                .withPaymentGateway("stripe")
                .withGatewayAccountCredentials(Collections.singletonList(credentialsParams))
                .withServiceName("a cool service")
                .build());

        boolean result = gatewayAccountCredentialsDao.hasActiveCredentials(gatewayAccountId);

        assertThat(result, is(false));
    }

    @Test
    public void findsCredentialByExternalId() {
        long gatewayAccountId = nextLong();
        String externalCredentialId = randomUuid();
        databaseTestHelper.addGatewayAccount(anAddGatewayAccountParams()
                .withAccountId(String.valueOf(gatewayAccountId))
                .withPaymentGateway("stripe")
                .withServiceName("a cool service")
                .build());
        GatewayAccountEntity gatewayAccountEntity = gatewayAccountDao.findById(gatewayAccountId).get();
        GatewayAccountCredentialsEntity gatewayAccountCredentialsEntity
                = new GatewayAccountCredentialsEntity(gatewayAccountEntity, "stripe", Map.of(), ACTIVE);
        gatewayAccountCredentialsEntity.setExternalId(externalCredentialId);
        gatewayAccountCredentialsDao.persist(gatewayAccountCredentialsEntity);

        Optional<GatewayAccountCredentialsEntity> optionalEntity = gatewayAccountCredentialsDao.findByExternalId(externalCredentialId);

        assertThat(optionalEntity.isPresent(), is(true));
        assertThat(optionalEntity.get().getExternalId(), is(externalCredentialId));
    }
}
