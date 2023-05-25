package uk.gov.pay.connector.resources;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import fj.data.Either;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import uk.gov.pay.connector.common.service.PatchRequestBuilder;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Collections.singletonList;
import static org.apache.commons.lang.RandomStringUtils.randomAlphanumeric;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static uk.gov.pay.connector.charge.resource.ChargesApiResource.AMOUNT_KEY;
import static uk.gov.pay.connector.charge.resource.ChargesApiResource.DELAYED_CAPTURE_KEY;
import static uk.gov.pay.connector.charge.resource.ChargesApiResource.EMAIL_KEY;
import static uk.gov.pay.connector.charge.resource.ChargesApiResource.LANGUAGE_KEY;
import static uk.gov.pay.connector.charge.resource.ChargesApiResource.MAX_AMOUNT;
import static uk.gov.pay.connector.charge.resource.ChargesApiResource.MIN_AMOUNT;
import static uk.gov.pay.connector.common.validator.ApiValidators.parseZonedDateTime;
import static uk.gov.pay.connector.common.validator.ApiValidators.validateChargeParams;
import static uk.gov.pay.connector.common.validator.ApiValidators.validateChargePatchParams;
import static uk.gov.pay.connector.common.validator.ApiValidators.validateFromDateIsBeforeToDate;


class ApiValidatorsTest {

    @Test
    void shouldValidateEmailLength_WhenPatchingAnEmail() {

        PatchRequestBuilder.PatchRequest request = PatchRequestBuilder.aPatchRequestBuilder(
                ImmutableMap.of(
                        "op", "replace",
                        "path", "email",
                        "value", "test@example.com"))
                .withValidOps(singletonList("replace"))
                .withValidPaths(ImmutableSet.of("email"))
                .build();
        assertThat(validateChargePatchParams(request), is(true));
    }

    @Test
    void shouldInvalidateEmailLength_WhenPatchingAnEmail() {

        PatchRequestBuilder.PatchRequest request = PatchRequestBuilder.aPatchRequestBuilder(
                ImmutableMap.of(
                        "op", "replace",
                        "path", "email",
                        "value", randomAlphanumeric(255) + "@example.com"))
                .withValidOps(singletonList("replace"))
                .withValidPaths(ImmutableSet.of("email"))
                .build();
        assertThat(validateChargePatchParams(request), is(false));
    }

    @Test
    void validateFromDateIsBeforeToDate_fromDateBeforeToDate() {
        Either<List<String>, Pair<ZonedDateTime, ZonedDateTime>> result = validateFromDateIsBeforeToDate("from_date", "2017-11-23T12:00:00Z",
                "to_date", "2017-11-23T15:00:00Z");

        assertThat(result.isRight(), is(true));
        assertThat(result.right().value(), is(Pair.of(ZonedDateTime.parse("2017-11-23T12:00:00Z"), ZonedDateTime.parse("2017-11-23T15:00:00Z"))));
    }

    @Test
    void validateFromDateIsBeforeToDate_fromDateAfterToDate() {
        Either<List<String>, Pair<ZonedDateTime, ZonedDateTime>> result = validateFromDateIsBeforeToDate("from_date", "2017-11-23T15:00:00Z",
                "to_date", "2017-11-23T12:00:00Z");

        assertThat(result.isLeft(), is(true));
        assertThat(result.left().value().get(0), is("query param 'to_date' must be later than 'from_date'"));
    }

    @Test
    void validateFromDateIsBeforeToDate_fromDateIsMissing() {
        Either<List<String>, Pair<ZonedDateTime, ZonedDateTime>> result = validateFromDateIsBeforeToDate("from_date", null,
                "to_date", "2017-11-23T15:00:00Z");

        assertThat(result.isLeft(), is(true));
        assertThat(result.left().value().get(0), is("query param 'from_date' not in correct format"));
    }

    @Test
    void validateFromDateIsBeforeToDate_toDateIsMissing() {
        Either<List<String>, Pair<ZonedDateTime, ZonedDateTime>> result = validateFromDateIsBeforeToDate("from_date", "2017-11-23T12:00:00Z",
                "to_date", null);

        assertThat(result.isLeft(), is(true));
        assertThat(result.left().value().get(0), is("query param 'to_date' not in correct format"));
    }

    @Test
    void validateFromDateIsBeforeToDate_fromDateIsInIncorrectFormat() {
        Either<List<String>, Pair<ZonedDateTime, ZonedDateTime>> result = validateFromDateIsBeforeToDate("from_date", "Thu, 23 Nov 2017 12:00:00 GMT",
                "to_date", "2017-11-23T15:00:00Z");

        assertThat(result.isLeft(), is(true));
        assertThat(result.left().value().get(0), is("query param 'from_date' not in correct format"));
    }

    @Test
    void validateFromDateIsBeforeToDate_toDateIsInIncorrectFormat() {
        Either<List<String>, Pair<ZonedDateTime, ZonedDateTime>> result = validateFromDateIsBeforeToDate("from_date", "2017-11-23T12:00:00Z",
                "to_date", "2017/11/23 03:00:00 PM");

        assertThat(result.isLeft(), is(true));
        assertThat(result.left().value().get(0), is("query param 'to_date' not in correct format"));
    }

    @Test
    void parseZonedDateTimeParsesZonedDateTimes() {
        String rawDate = "2018-06-20T00:00:00Z";
        Optional<ZonedDateTime> maybeDate = parseZonedDateTime(rawDate);

        assertThat(maybeDate.isPresent(), is(true));

        ZonedDateTime date = maybeDate.get();
        assertThat(date.getDayOfMonth(), is(20));
        assertThat(date.getMonthValue(), is(6));
        assertThat(date.getYear(), is(2018));
    }

    @Test
    void validateChargeParams_shouldAccept_whenEmailAndAmountValid() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(EMAIL_KEY, "anonymous@example.com");
        inputData.put(AMOUNT_KEY, "500");

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.empty()));
    }

    @Test
    void validateChargeParams_shouldRejectEmail_when255Characters() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(EMAIL_KEY, RandomStringUtils.randomAlphanumeric(255));

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.of(Collections.singletonList(EMAIL_KEY))));
    }

    @Test
    void validateChargeParams_shouldRejectAmount_whenBelowMinAmount() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(AMOUNT_KEY, String.valueOf(MIN_AMOUNT - 1));

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.of(Collections.singletonList(AMOUNT_KEY))));
    }

    @Test
    void validateChargeParams_shouldRejectAmount_whenAboveMaxAmount() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(AMOUNT_KEY, String.valueOf(MAX_AMOUNT + 1));

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.of(Collections.singletonList(AMOUNT_KEY))));
    }

    @Test
    void validateChargeParams_shouldRejectAmount_whenNotParsableAsLong() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(AMOUNT_KEY, "this is not a number");

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.of(Collections.singletonList(AMOUNT_KEY))));
    }

    @Test
    void validateChargeParams_shouldAcceptLanguage_whenEn() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(LANGUAGE_KEY, "en");

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.empty()));
    }

    @Test
    void validateChargeParams_shouldAcceptLanguage_whenCy() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(LANGUAGE_KEY, "cy");

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.empty()));
    }

    @Test
    void validateChargeParams_shouldRejectLanguage_whenEmptyString() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(LANGUAGE_KEY, "");

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.of(Collections.singletonList("language"))));
    }

    @Test
    void validateChargeParams_shouldRejectLanguage_whenNull() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(LANGUAGE_KEY, null);

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.of(Collections.singletonList("language"))));
    }

    @Test
    void validateChargeParams_shouldRejectLanguage_whenIsFr() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(LANGUAGE_KEY, "fr");

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.of(Collections.singletonList("language"))));
    }

    @Test
    void validateChargeParams_shouldAcceptDelayedCapture_whenTrue() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(DELAYED_CAPTURE_KEY, "true");

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.empty()));
    }

    @Test
    void validateChargeParams_shouldAcceptDelayedCapture_whenFalse() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(DELAYED_CAPTURE_KEY, "false");

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.empty()));
    }

    @Test
    void validateChargeParams_shouldAcceptDelayedCapture_whenNotTrueOrFalse() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(DELAYED_CAPTURE_KEY, "maybe");

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result, is(Optional.of(Collections.singletonList("delayed_capture"))));
    }
    
    @Test
    void validateChargeParams_shouldRejectEmailAndAmount_whenBothInvalid() {
        Map<String, String> inputData = new HashMap<>();
        inputData.put(EMAIL_KEY, RandomStringUtils.randomAlphanumeric(255));
        inputData.put(AMOUNT_KEY, String.valueOf(MAX_AMOUNT + 1));

        Optional<List<String>> result = validateChargeParams(inputData);

        assertThat(result.get().containsAll(Arrays.asList(AMOUNT_KEY, EMAIL_KEY)), is(true));
    }
}
