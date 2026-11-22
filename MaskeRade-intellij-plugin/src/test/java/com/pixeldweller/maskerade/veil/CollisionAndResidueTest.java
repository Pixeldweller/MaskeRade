package com.pixeldweller.maskerade.veil;

import com.pixeldweller.maskerade.SampleFixture;
import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.model.TermKind;
import com.pixeldweller.maskerade.scan.CommonWords;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollisionAndResidueTest {

    @Test
    void collisionWithThePlaceholderIsDetected() throws Exception {
        // README: choosing "Nordwind" for "Falkenrath" collides with Wind.NORDWIND in the sample
        var engine = VeilEngine.of(List.of(Term.of("Falkenrath", "Nordwind", TermKind.COMPANY)), CommonWords.builtIn());
        var rel = "src/main/java/de/falkenrath/kranich/weather/Wind.java";
        var text = Files.readString(SampleFixture.sampleRoot().resolve(rel));
        var collisions = Collisions.find(engine, rel, text);
        assertFalse(collisions.isEmpty());
        assertEquals("NORDWIND", collisions.getFirst().found());
        // and the round trip fails for that file, so it would never be mirrored
        assertNotEquals(text, engine.unveil(engine.veil(text)));
        assertTrue(Collisions.occursIn("Nordwind", text));
        assertFalse(Collisions.occursIn("Seewald", text));
    }

    @Test
    void generatorAvoidsTakenPlaceholdersAndIsDeterministic() {
        var gen = new PlaceholderGenerator("seed-1");
        var first = gen.suggest(TermKind.COMPANY, "Falkenrath Logistik", p -> false);
        assertEquals(first, new PlaceholderGenerator("seed-1").suggest(TermKind.COMPANY, "Falkenrath Logistik", p -> false));
        assertEquals(2, first.split(" ").length);
        var other = gen.suggest(TermKind.COMPANY, "Falkenrath Logistik", p -> p.equals(first));
        assertNotEquals(first, other);
        assertTrue(gen.suggest(TermKind.MAIL, "j.wessling@falkenrath-logistik.de", p -> false).endsWith("@example.org"));
        assertTrue(gen.suggest(TermKind.HOST, "build01.falkenrath.lan", p -> false).endsWith(".example"));
        var secret = gen.suggest(TermKind.SECRET, "FLK-LIVE-0000-DUMMY-DO-NOT-USE", p -> false);
        assertEquals(30, secret.length());
        assertTrue(TermValidator.problemsOf(Term.of("FLK-LIVE-0000-DUMMY-DO-NOT-USE", secret, TermKind.SECRET)).isEmpty());
        var pseudonym = gen.projectPseudonym("C:/Users/someone/projects/kranich");
        assertFalse(pseudonym.contains("kranich") || pseudonym.contains("someone"));
    }

    @Test
    void residueSearchIsLoose() {
        var finder = new ResidueFinder(List.of(Term.of("Falkenrath Logistik", "Nordwind Handel", TermKind.COMPANY)),
                CommonWords.builtIn());
        assertFalse(finder.findInText("String s = \"f_a_l_k_e_n_r_a_t_h\";").isEmpty());
        assertFalse(finder.findInText("F-A-L-K-E-N-R-A-T-H").isEmpty());
        assertFalse(finder.findInText("nordwind FALKENRATH handel").isEmpty());
        assertTrue(finder.findInText("Nordwind Handel GmbH").isEmpty());

        var base64 = Base64.getEncoder().encodeToString("Falkenrath Logistik GmbH".getBytes(StandardCharsets.UTF_8));
        var hits = finder.findInText("static final String X = \"" + base64 + "\";");
        assertTrue(hits.stream().anyMatch(h -> h.form() == ResidueFinder.Form.BASE64), hits.toString());

        var escaped = finder.findInText("String s = \"\\u0046alkenrath\";");
        assertTrue(escaped.stream().anyMatch(h -> h.form() == ResidueFinder.Form.UNICODE_ESCAPE), escaped.toString());

        var utf16 = "xx Falkenrath yy".getBytes(StandardCharsets.UTF_16LE);
        assertTrue(finder.findInBytes(utf16).stream().anyMatch(h -> h.form() == ResidueFinder.Form.UTF16));
    }

    @Test
    void sampleLogoIsReported() throws Exception {
        var bytes = Files.readAllBytes(SampleFixture.sampleRoot().resolve("src/main/resources/logo.png"));
        var hits = SampleFixture.residueFinder().findInBytes(bytes);
        assertTrue(hits.stream().anyMatch(h -> h.term().real.equals("Falkenrath Logistik")), hits.toString());
    }

    @Test
    void proxyListRoundTrip() {
        var terms = SampleFixture.terms();
        var text = ProxyListFormat.export(terms, CommonWords.builtIn());
        assertTrue(text.contains("falkenrath_logistik = seewald_spedition"), text);
        assertTrue(text.contains("FALKENRATH_LOGISTIK = SEEWALD_SPEDITION"), text);
        assertTrue(text.contains("Falkenrath = Seewald"), text);
        var parsed = ProxyListFormat.parse(text);
        assertEquals(terms.size(), parsed.size(), parsed.toString());
        for (int i = 0; i < terms.size(); i++) {
            assertEquals(terms.get(i).key(), parsed.get(i).key());
            assertEquals(terms.get(i).kind, parsed.get(i).kind);
        }
        var plain = ProxyListFormat.parse("# proxy list\nFalkenrath = Nordwind\nbuild01.falkenrath.lan = host.example\n");
        assertEquals(2, plain.size());
        assertEquals(TermKind.HOST, plain.get(1).kind);
    }
}
