package de.falkenrath.kranich.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Client for the (fictional) dispatch API of Falkenrath Logistik.
 *
 * <p>MaskeRade cases:
 * <ul>
 *   <li>"word part in identifiers": {@code FalkenrathApiClient}, {@code FALKENRATH_API_URL}</li>
 *   <li>"external contract": the URL points to a real server of the company — the mirror points to
 *       {@code *.example} instead, so a test calling it would behave differently there</li>
 *   <li>"secret": {@code falkenrath.api.key} is set in application.properties</li>
 * </ul>
 *
 * @author Jorinde Wessling
 */
@Component
public class FalkenrathApiClient {

    public static final String FALKENRATH_API_URL = "https://api.falkenrath-logistik.de/v1";

    /** Build agent that publishes the jar (internal host). */
    public static final String BUILD_HOST = "build01.falkenrath.lan";

    /** Fallback address of the dispatch gateway (private network). */
    public static final String GATEWAY_IP = "10.20.30.40";

    private final String apiKey;
    private final String baseUrl;

    public FalkenrathApiClient(@Value("${falkenrath.api.key}") String apiKey,
                               @Value("${falkenrath.api.url:" + FALKENRATH_API_URL + "}") String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String toursEndpoint() {
        return baseUrl + "/tours";
    }

    /** Never log the key itself. */
    public String maskedKey() {
        return apiKey.substring(0, 4) + "…";
    }
}
