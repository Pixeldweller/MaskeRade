package de.falkenrath.kranich.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * A delivery order that is assigned to a stop of a tour.
 *
 * <p>MaskeRade case "common words": {@code Order} is no candidate.
 */
public record Order(@NotBlank String customerId, @Positive int pallets) {
}
