package de.falkenrath.kranich.weather;

/**
 * Wind directions for the storm warning of a tour.
 *
 * <p>MaskeRade case "collision": {@code NORDWIND} contains the word {@code Nordwind}. If you choose
 * {@code Nordwind} as placeholder for {@code Falkenrath} (see README), "Check collisions" reports it — the
 * placeholder already occurs in the original, so unveiling could not tell both apart.
 */
public enum Wind {
    NORDWIND, OSTWIND, SUEDWIND, WESTWIND;

    public boolean isHeadwindFor(Wind travelDirection) {
        return ordinal() == (travelDirection.ordinal() + 2) % values().length;
    }
}
