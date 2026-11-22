package de.falkenrath.kranich;

import de.falkenrath.kranich.tour.TourService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Logs how many tours are planned after start-up.
 *
 * <p>MaskeRade case "framework override": {@link #run(String...)} implements {@link CommandLineRunner#run} and must
 * never be renamed; neither must the Spring annotations.
 *
 * @author Jorinde Wessling
 */
@Component
public class StartupReport implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReport.class);

    private final TourService tourService;

    public StartupReport(TourService tourService) {
        this.tourService = tourService;
    }

    @Override
    public void run(String... args) {
        log.info("Kranich ready: {} tours planned", tourService.findAll().size());
    }
}
