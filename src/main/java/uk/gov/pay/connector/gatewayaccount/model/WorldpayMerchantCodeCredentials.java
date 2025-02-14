package uk.gov.pay.connector.gatewayaccount.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorldpayMerchantCodeCredentials {

    @JsonView({GatewayCredentials.Views.Api.class})
    private String merchantCode;
    @JsonView({GatewayCredentials.Views.Api.class})
    private String username;
    @Schema(hidden = true)
    private String password;

    public WorldpayMerchantCodeCredentials() {
        // Janet Jackson
    }

    public WorldpayMerchantCodeCredentials(String merchantCode, String username, String password) {
        this.merchantCode = merchantCode;
        this.username = username;
        this.password = password;
    }

    public String getMerchantCode() {
        return merchantCode;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        var that = (WorldpayMerchantCodeCredentials) other;

        return Objects.equals(merchantCode, that.merchantCode)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(merchantCode, username, password);
    }

}
