package de.falkenrath.kranich.export;

import de.falkenrath.kranich.tour.Tour;

import java.util.List;

/** Writes tours into some text format. */
public interface TourExporter {

    String export(List<Tour> tours);
}
