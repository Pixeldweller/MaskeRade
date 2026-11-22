package de.falkenrath.kranich;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MaskeRade case "Base64": the string below is the company name, Base64-encoded. MaskeRade does not replace it
 * silently but reports it in the residue search ("encoded hit"). The assertion does not compare it with the plain
 * name, so the test stays green in the mirror as well.
 */
class CompanyNameEncodingTest {

    static final String ENCODED_COMPANY = "RmFsa2VucmF0aCBMb2dpc3RpayBHbWJI";

    @Test
    void decodesToALimitedCompany() {
        var decoded = new String(Base64.getDecoder().decode(ENCODED_COMPANY), StandardCharsets.UTF_8);
        assertThat(decoded).endsWith(" GmbH");
    }
}
