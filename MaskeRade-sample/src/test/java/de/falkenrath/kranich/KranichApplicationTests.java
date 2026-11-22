package de.falkenrath.kranich;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the application and calls the REST API — path and JSON field are renamed consistently in the mirror,
 * so this test is green there too.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KranichApplicationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void listsTours() throws Exception {
        mvc.perform(get("/api/kranich/touren"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].falkenrathId").exists());
    }

    @Test
    void findsTheDemoTour() throws Exception {
        mvc.perform(get("/api/kranich/touren/FLK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.falkenrathId").value("FLK-1"))
                .andExpect(jsonPath("$.customer.name").value("Brunhild Osterkamp"));
    }

    @Test
    void createsATour() throws Exception {
        mvc.perform(post("/api/kranich/touren")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"K-1002\",\"pallets\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.city").value("Cuxhaven"));
    }

    @Test
    void rejectsAnInvalidOrder() throws Exception {
        mvc.perform(post("/api/kranich/touren")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"\",\"pallets\":0}"))
                .andExpect(status().isBadRequest());
    }
}
