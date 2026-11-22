package de.falkenrath.kranich.tour;

import de.falkenrath.kranich.customer.CustomerRepository;
import de.falkenrath.kranich.order.Order;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the planned tours in memory. MaskeRade case "common words": {@code TourService} is no candidate.
 */
@Service
public class TourService {

    private final FalkenrathTourPlanner planner;
    private final CustomerRepository customers;
    private final Map<String, Tour> tours = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public TourService(FalkenrathTourPlanner planner, CustomerRepository customers) {
        this.planner = planner;
        this.customers = customers;
        // one demo tour so the REST API returns something
        customers.findAll().stream().findFirst().ifPresent(c -> create(new Order(c.id(), 4)));
    }

    public List<Tour> findAll() {
        return List.copyOf(tours.values());
    }

    public Optional<Tour> findById(String falkenrathId) {
        return Optional.ofNullable(tours.get(falkenrathId));
    }

    public Tour create(Order order) {
        var customer = customers.findById(order.customerId())
                .orElseThrow(() -> new NoSuchElementException("Unknown customer " + order.customerId()));
        var id = "FLK-" + sequence.incrementAndGet();
        var stop = new Stop(1, customer.city(), LocalTime.of(8, 0), order);
        var tour = planner.plan(id, customer, List.of(stop));
        tours.put(id, tour);
        return tour;
    }
}
