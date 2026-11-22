package de.falkenrath.kranich.tour;

import java.util.List;

/**
 * The ordered stops of a tour plus the driving distance.
 * MaskeRade case "common words": {@code Route} is no candidate.
 */
public record Route(List<Stop> stops, double kilometres) {

    public Route {
        stops = List.copyOf(stops);
    }

    public int size() {
        return stops.size();
    }
}
