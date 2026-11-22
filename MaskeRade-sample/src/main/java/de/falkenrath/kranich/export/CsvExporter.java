package de.falkenrath.kranich.export;

import de.falkenrath.kranich.tour.Tour;

import java.util.List;
import java.util.stream.Collectors;

/** Exports tours as CSV ({@code falkenrath_id;customer;stops}). Loaded by reflection, see {@link ExporterFactory}. */
public class CsvExporter implements TourExporter {

    static final String HEADER = "falkenrath_id;customer;stops";

    @Override
    public String export(List<Tour> tours) {
        return tours.stream()
                .map(t -> t.falkenrathId() + ";" + t.customer().name() + ";" + t.route().size())
                .collect(Collectors.joining("\n", HEADER + "\n", "\n"));
    }
}
