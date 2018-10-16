package uk.gov.pay.connector.gateway.templates;

import static uk.gov.pay.connector.gateway.OrderRequestBuilder.TemplateData;

public interface PayloadBuilder {

    String buildWith(TemplateData templateData);
}
