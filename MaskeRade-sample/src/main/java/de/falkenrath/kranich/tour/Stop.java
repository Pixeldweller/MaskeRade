package de.falkenrath.kranich.tour;

import de.falkenrath.kranich.order.Order;

import java.time.LocalTime;

/**
 * One stop of a tour. MaskeRade case "common words": {@code Stop} is no candidate.
 */
public record Stop(int sequence, String address, LocalTime plannedAt, Order order) {
}
