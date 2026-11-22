package de.falkenrath.kranich;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Kranich tour planner of Falkenrath Logistik GmbH.
 *
 * <p>MaskeRade case "package prefix": {@code de.falkenrath.kranich} contains the company and the codename,
 * the package directories are renamed in the mirror.
 * MaskeRade case "word part in identifiers": {@code KranichApplication}.
 *
 * @author Jorinde Wessling
 */
@SpringBootApplication
public class KranichApplication {

    public static void main(String[] args) {
        SpringApplication.run(KranichApplication.class, args);
    }
}
