package de.falkenrath.kranich.weather;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WindTest {

    @Test
    void nordwindIsHeadwindWhenDrivingSouth() {
        assertThat(Wind.NORDWIND.isHeadwindFor(Wind.SUEDWIND)).isTrue();
        assertThat(Wind.NORDWIND.isHeadwindFor(Wind.OSTWIND)).isFalse();
    }
}
