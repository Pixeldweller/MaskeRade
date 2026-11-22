package de.falkenrath.kranich.customer;

/**
 * A customer of the tour planning.
 *
 * <p>MaskeRade case "common words": {@code Customer} is everyday vocabulary and no candidate.
 */
public record Customer(String id, String name, String city) {

    @Override
    public String toString() {
        return name + " (" + city + ")";
    }
}
