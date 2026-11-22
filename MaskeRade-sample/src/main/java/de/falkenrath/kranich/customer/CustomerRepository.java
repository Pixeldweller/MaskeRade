package de.falkenrath.kranich.customer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * Reads the customers from {@code kunden.json} on the classpath.
 *
 * <p>The list was originally exported from C:\Users\jwessling\Desktop\touren.xlsx
 * (MaskeRade case "Windows path with user name").
 *
 * @author Jorinde Wessling
 */
@Repository
public class CustomerRepository {

    private static final String RESOURCE = "/kunden.json";

    private final List<Customer> customers;

    public CustomerRepository(ObjectMapper objectMapper) {
        try (InputStream in = CustomerRepository.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " missing");
            customers = List.copyOf(objectMapper.readValue(in, new TypeReference<List<Customer>>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Customer> findAll() {
        return customers;
    }

    public Optional<Customer> findById(String id) {
        return customers.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public Optional<Customer> findByName(String name) {
        return customers.stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst();
    }
}
