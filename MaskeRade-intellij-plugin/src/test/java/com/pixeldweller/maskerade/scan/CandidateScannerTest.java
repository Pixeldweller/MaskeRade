package com.pixeldweller.maskerade.scan;

import com.pixeldweller.maskerade.SampleFixture;
import com.pixeldweller.maskerade.model.Candidate;
import com.pixeldweller.maskerade.model.TermKind;
import com.pixeldweller.maskerade.util.TextCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The candidates the sample README promises. */
class CandidateScannerTest {

    private static List<Candidate> candidates;
    private static Map<String, Candidate> byKey;

    @BeforeAll
    static void scanSample() throws Exception {
        var input = new CandidateScanner.Input();
        var root = SampleFixture.sampleRoot();
        for (var rel : SampleFixture.sampleFiles()) {
            var bytes = Files.readAllBytes(root.resolve(rel));
            if (TextCodec.isBinary(rel, bytes)) continue;
            input.documents.add(new CandidateScanner.Document(rel, TextCodec.decode(bytes).text(), rel.contains("src/test/")));
        }
        input.context = new CandidateScanner.Context(Set.of(), Set.of(), "MaskeRade-sample", Set.of("kranich-tourplaner"), "someone");
        candidates = CandidateScanner.scan(input);
        byKey = candidates.stream().collect(Collectors.toMap(Candidate::key, Function.identity(), (a, b) -> a));
    }

    private static Candidate expect(String key, TermKind kind, int minScore) {
        var c = byKey.get(key);
        assertNotNull(c, "missing candidate " + key + " in " + candidates.stream().map(Candidate::key).limit(40).toList());
        if (kind != null) assertEquals(kind, c.kind(), key + ": " + c.reasons());
        assertTrue(c.score() >= minScore, key + " scored " + c.score() + " " + c.reasons());
        return c;
    }

    @Test
    void companyAndCodename() {
        expect("falkenrath", TermKind.COMPANY, 60);
        expect("kranich", TermKind.PROJECT, 60);
        expect("falkenrath logistik", TermKind.COMPANY, 60);
        assertTrue(byKey.get("falkenrath").reasons().contains("package prefix"));
    }

    @Test
    void persons() {
        expect("jorinde wessling", TermKind.PERSON, 60);
        expect("brunhild osterkamp", null, 50);
        expect("wessling", TermKind.PERSON, 40);
        expect("osterkamp", null, 30);
    }

    @Test
    void fixedKinds() {
        expect("j.wessling@falkenrath-logistik.de", TermKind.MAIL, 60);
        expect("build01.falkenrath.lan", TermKind.HOST, 60);
        expect("db.falkenrath.lan", TermKind.HOST, 60);
        expect("10.20.30.40", TermKind.HOST, 50);
        expect("jdbc:postgresql://db.falkenrath.lan:5432/kranich", TermKind.HOST, 60);
        expect("jwessling", TermKind.USERNAME, 70);
        var secret = expect("FLK-LIVE-0000-DUMMY-DO-NOT-USE", TermKind.SECRET, 100);
        assertTrue(secret.secret());
    }

    @Test
    void externalContractsAreMarked() {
        assertTrue(byKey.get("kranich").contract(), byKey.get("kranich").reasons().toString());
        assertTrue(byKey.get("falkenrath").contract());
    }

    @Test
    void everydayWordsAreNoCandidates() {
        for (var word : List.of("service", "order", "tour", "route", "customer", "stop", "planner", "touren", "tourplaner",
                "repository", "exporter", "application", "string", "override", "compare", "run", "logistik")) {
            assertFalse(byKey.containsKey(word), word + " must not be a candidate");
        }
    }

    @Test
    void ownPlaceholderWordsAreNoCandidates() {
        assertFalse(byKey.containsKey("nordwind"));
    }
}
