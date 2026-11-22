package de.falkenrath.kranich.tour;

import java.util.Comparator;

/**
 * Orders stops by planned time, then by sequence.
 *
 * <p>MaskeRade case "framework override": {@link #compare(Stop, Stop)} implements {@link Comparator#compare}.
 */
public class StopOrder implements Comparator<Stop> {

    @Override
    public int compare(Stop a, Stop b) {
        int byTime = a.plannedAt().compareTo(b.plannedAt());
        return byTime != 0 ? byTime : Integer.compare(a.sequence(), b.sequence());
    }
}
