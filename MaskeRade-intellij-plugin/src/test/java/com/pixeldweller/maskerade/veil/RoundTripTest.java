package com.pixeldweller.maskerade.veil;

import com.pixeldweller.maskerade.SampleFixture;
import com.pixeldweller.maskerade.util.TextCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** unveil(veil(x)) == x, byte for byte. */
class RoundTripTest {

    private final VeilEngine engine = SampleFixture.engine();

    @Test
    void everySampleFileRoundTripsByteForByte() throws Exception {
        var root = SampleFixture.sampleRoot();
        var failures = new ArrayList<String>();
        int veiledFiles = 0;
        for (var rel : SampleFixture.sampleFiles()) {
            var bytes = Files.readAllBytes(root.resolve(rel));
            if (TextCodec.isBinary(rel, bytes)) continue;
            var decoded = TextCodec.decode(bytes);
            var veiled = engine.veil(decoded.text());
            if (!veiled.equals(decoded.text())) veiledFiles++;
            var back = decoded.encode(engine.unveil(veiled));
            if (!java.util.Arrays.equals(bytes, back)) failures.add(rel);
            if (!engine.unveilPath(engine.veilPath(rel)).equals(rel)) failures.add("path " + rel);
        }
        assertTrue(failures.isEmpty(), "not reversible: " + failures);
        assertTrue(veiledFiles > 10, "only " + veiledFiles + " files contained terms");
    }

    @Test
    void crlfAndBomSurvive() throws Exception {
        var text = "﻿package de.falkenrath.kranich;\r\n\r\n// Falkenrath Logistik GmbH\r\nclass KranichApp {}\r\n";
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        var decoded = TextCodec.decode(bytes);
        assertEquals(3, decoded.bom().length, "BOM detected");
        var veiled = decoded.encode(engine.veil(decoded.text()));
        var veiledText = new String(veiled, StandardCharsets.UTF_8);
        assertTrue(veiledText.startsWith("﻿package de.seewald.reiher;\r\n"), veiledText);
        assertTrue(veiledText.contains("\r\n// Seewald Spedition GmbH\r\n"), veiledText);
        var back = decoded.encode(engine.unveil(TextCodec.decode(veiled).text()));
        assertArrayEquals(bytes, back);
    }

    @Test
    void latin1AndUtf16FilesSurvive() throws Exception {
        var latin1 = "Grüße von Falkenrath Logistik\n".getBytes(StandardCharsets.ISO_8859_1);
        var d1 = TextCodec.decode(latin1);
        assertEquals(StandardCharsets.ISO_8859_1, d1.charset());
        assertArrayEquals(latin1, d1.encode(engine.unveil(engine.veil(d1.text()))));
        assertNotEquals(d1.text(), engine.veil(d1.text()));

        var utf16 = new byte[0];
        var body = "Kranich\r\n".getBytes(StandardCharsets.UTF_16LE);
        utf16 = new byte[body.length + 2];
        utf16[0] = (byte) 0xFF;
        utf16[1] = (byte) 0xFE;
        System.arraycopy(body, 0, utf16, 2, body.length);
        var d2 = TextCodec.decode(utf16);
        assertEquals(StandardCharsets.UTF_16LE, d2.charset());
        assertEquals("Reiher\r\n", engine.veil(d2.text()));
        assertArrayEquals(utf16, d2.encode(engine.unveil(engine.veil(d2.text()))));
    }
}
