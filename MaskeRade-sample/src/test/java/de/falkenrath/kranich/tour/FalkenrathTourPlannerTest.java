package de.falkenrath.kranich.tour;

import de.falkenrath.kranich.customer.Customer;
import de.falkenrath.kranich.order.Order;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** MaskeRade case "person": the customer Brunhild Osterkamp also appears in test data. */
class FalkenrathTourPlannerTest {

    private final FalkenrathTourPlanner planner = new FalkenrathTourPlanner();

    @Test
    void ordersStopsByTimeThenSequence() {
        var customer = new Customer("K-1001", "Brunhild Osterkamp", "Bremerhaven");
        var order = new Order("K-1001", 1);
        var late = new Stop(1, "Am Deich 1", LocalTime.of(14, 0), order);
        var early = new Stop(2, "Hafenstrasse 7", LocalTime.of(8, 30), order);
        var alsoEarly = new Stop(3, "Kaje 3", LocalTime.of(8, 30), order);

        var tour = planner.plan("FLK-7", customer, List.of(late, alsoEarly, early));

        assertThat(tour.route().stops()).containsExactly(early, alsoEarly, late);
        assertThat(tour.route().kilometres()).isEqualTo(3 * FalkenrathTourPlanner.KILOMETRES_PER_STOP);
        assertThat(tour.toString()).isEqualTo("Tour FLK-7 for Brunhild Osterkamp (Bremerhaven) with 3 stops");
    }
}
