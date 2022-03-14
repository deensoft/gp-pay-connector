package uk.gov.pay.connector.agreement.dao;

import com.google.inject.Provider;
import uk.gov.pay.connector.agreement.model.AgreementEntity;
import uk.gov.pay.connector.common.dao.JpaDao;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.Optional;

public class AgreementDao extends JpaDao<AgreementEntity> {

    @Inject
    public AgreementDao(Provider<EntityManager> entityManager) {
        super(entityManager);
    }

    public Optional<AgreementEntity> findById(Long agreementId) {
        return super.findById(AgreementEntity.class, agreementId);
    }

}