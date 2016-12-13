package uk.gov.pay.connector.service.smartpay;

import com.google.common.io.Resources;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;

import static com.google.common.io.Resources.getResource;
import static org.custommonkey.xmlunit.XMLAssert.assertXMLEqual;
import static uk.gov.pay.connector.service.OrderCaptureRequestBuilder.aSmartpayOrderCaptureRequest;

public class SmartpayOrderCaptureRequestBuilderTest {

    @Test
    public void shouldGenerateValidOrderCapturePayload() throws Exception {
        String actualRequest = aSmartpayOrderCaptureRequest("capture")
                .withMerchantCode("MerchantAccount")
                .withTransactionId("MyTransactionId")
                .withAmount("2000")
                .build();

        assertXMLEqual(expectedOrderSubmitPayload("templates/smartpay/valid-capture-smartpay-request.xml"), actualRequest);
    }

    @Test
    public void shouldGenerateValidOrderCapturePayload_withSpecialCharactersInStrings() throws Exception {
        String actualRequest = aSmartpayOrderCaptureRequest("capture")
                .withMerchantCode("MerchantAccount")
                .withTransactionId("MyTransactionId & <!-- >")
                .withAmount("2000")
                .build();

        assertXMLEqual(expectedOrderSubmitPayload("templates/smartpay/special-char-valid-capture-smartpay-request.xml"), actualRequest);
    }

    private String expectedOrderSubmitPayload(String resourceName) throws IOException {
        return Resources.toString(getResource(resourceName), Charset.defaultCharset());
    }
}
