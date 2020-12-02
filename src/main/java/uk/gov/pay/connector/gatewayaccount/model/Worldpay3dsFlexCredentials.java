package uk.gov.pay.connector.gatewayaccount.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.util.Objects;

@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
public class Worldpay3dsFlexCredentials {

    private String issuer;
    private String organisationalUnitId;

    @JsonIgnore
    private String jwtMacKey;

    public Worldpay3dsFlexCredentials(String issuer, String organisationalUnitId, String jwtMacKey) {
        this.issuer = issuer;
        this.organisationalUnitId = organisationalUnitId;
        this.jwtMacKey = jwtMacKey;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getOrganisationalUnitId() {
        return organisationalUnitId;
    }

    public String getJwtMacKey() {
        return jwtMacKey;
    }

    public static Worldpay3dsFlexCredentials fromEntity(Worldpay3dsFlexCredentialsEntity entity) {
        return new Worldpay3dsFlexCredentials(entity.getIssuer(), entity.getOrganisationalUnitId(), entity.getJwtMacKey());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Worldpay3dsFlexCredentials that = (Worldpay3dsFlexCredentials) o;
        return Objects.equals(issuer, that.issuer) &&
                Objects.equals(organisationalUnitId, that.organisationalUnitId) &&
                Objects.equals(jwtMacKey, that.jwtMacKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(issuer, organisationalUnitId, jwtMacKey);
    }
}
