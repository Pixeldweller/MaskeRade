package de.falkenrath.kranich.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerRepositoryTest {

    private final CustomerRepository repository = new CustomerRepository(new ObjectMapper());

    @Test
    void readsCustomersFromJson() {
        assertThat(repository.findAll()).hasSize(3);
        assertThat(repository.findByName("brunhild osterkamp"))
                .get()
                .extracting(Customer::id)
                .isEqualTo("K-1001");
    }
}
