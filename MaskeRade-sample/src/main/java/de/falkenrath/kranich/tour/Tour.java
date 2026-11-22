package de.falkenrath.kranich.tour;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.falkenrath.kranich.customer.Customer;

/**
 * A planned tour.
 *
 * <p>MaskeRade cases:
 * <ul>
 *   <li>"word part in identifiers": the field {@code falkenrathId}</li>
 *   <li>"JSON contract": {@code @JsonProperty("falkenrathId")} is read by other systems — renaming it in the
 *       mirror is consistent inside the project, but the mirror's JSON differs from the original's
 *       ("external contract")</li>
 * </ul>
 */
public record Tour(
        @JsonProperty("falkenrathId") String falkenrathId,
        Customer customer,
        Route route) {

    @Override
    public String toString() {
        return "Tour " + falkenrathId + " for " + customer + " with " + route.size() + " stops";
    }
}
