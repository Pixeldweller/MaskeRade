package de.falkenrath.kranich.export;

import de.falkenrath.kranich.customer.Customer;
import de.falkenrath.kranich.tour.Route;
import de.falkenrath.kranich.tour.Tour;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** MaskeRade case "reflection string": still loads the exporter in the mirror. */
class ExporterFactoryTest {

    @Test
    void loadsTheCsvExporterByName() {
        var exporter = ExporterFactory.createDefault();
        assertThat(exporter).isInstanceOf(CsvExporter.class);

        var tour = new Tour("FLK-9", new Customer("K-1003", "Gartenbau am Deich", "Husum"), new Route(List.of(), 0));
        assertThat(exporter.export(List.of(tour)))
                .isEqualTo(CsvExporter.HEADER + "\nFLK-9;Gartenbau am Deich;0\n");
    }
}
