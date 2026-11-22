package de.falkenrath.kranich.tour;

import de.falkenrath.kranich.customer.Customer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans tours the way Falkenrath Logistik dispatches them: stops ordered by time, 18 km per stop on average.
 *
 * <p>MaskeRade case "word part in identifiers": {@code FalkenrathTourPlanner} — only the word part
 * {@code Falkenrath} is replaced, {@code Tour} and {@code Planner} stay.
 *
 * @author Jorinde Wessling
 */
@Component
public class FalkenrathTourPlanner {

    static final double KILOMETRES_PER_STOP = 18.0;

    private final StopOrder stopOrder = new StopOrder();

    public Tour plan(String falkenrathId, Customer customer, List<Stop> stops) {
        var ordered = new ArrayList<>(stops);
        ordered.sort(stopOrder);
        return new Tour(falkenrathId, customer, new Route(ordered, ordered.size() * KILOMETRES_PER_STOP));
    }
}
