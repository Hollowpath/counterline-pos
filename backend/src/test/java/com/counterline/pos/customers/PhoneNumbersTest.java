package com.counterline.pos.customers;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumbersTest {

    @ParameterizedTest
    @CsvSource({
            "011-2345 6782,     +601123456782",
            "01123456782,       +601123456782",
            "+60 11-2345 6782,  +601123456782",
            "601123456782,      +601123456782",
            "0065 9123 4567,    +6591234567",
            "(03) 7722.1234,    +60377221234",
            "+60 011-2345 6782, +601123456782",
            "0060 011 2345 6782, +601123456782",
            "60 01123456782,    +601123456782",
            "+65 0912 3456,     +6509123456",
    })
    void normalizesToE164WithTheOutletCountryCode(String input, String expected) {
        assertThat(PhoneNumbers.normalize(input, "60")).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "abc", "+0123456789", "011-2345-53x2", "+1234567890123456"})
    void rejectsWhatCannotBeAPhoneNumber(String input) {
        assertThat(PhoneNumbers.normalize(input, "60")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "0112345,     60112345",
            "+6011,       6011",
            "2345 67,     234567",
            "+60 0112345,  60112345",
            "600112345,    60112345",
    })
    void searchTermsInLocalFormatMatchStoredNumbers(String input, String expected) {
        assertThat(PhoneNumbers.searchDigits(input, "60")).isEqualTo(expected);
    }
}
