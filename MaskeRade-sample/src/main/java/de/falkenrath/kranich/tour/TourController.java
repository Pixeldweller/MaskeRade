package de.falkenrath.kranich.tour;

import de.falkenrath.kranich.order.Order;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API of the tour planning.
 *
 * <p>MaskeRade case "external contract": the path {@code /api/kranich/touren} is called by other systems.
 * Inside the mirror it is renamed consistently (tests keep working), but the mirror's API differs from the original.
 */
@RestController
@RequestMapping("/api/kranich/touren")
public class TourController {

    private final TourService tourService;

    public TourController(TourService tourService) {
        this.tourService = tourService;
    }

    @GetMapping
    public List<Tour> findAll() {
        return tourService.findAll();
    }

    @GetMapping("/{falkenrathId}")
    public ResponseEntity<Tour> findById(@PathVariable String falkenrathId) {
        return ResponseEntity.of(tourService.findById(falkenrathId));
    }

    @PostMapping
    public Tour create(@Valid @RequestBody Order order) {
        return tourService.create(order);
    }
}
