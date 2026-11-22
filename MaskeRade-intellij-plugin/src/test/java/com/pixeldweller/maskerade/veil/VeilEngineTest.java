package com.pixeldweller.maskerade.veil;

import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.model.TermKind;
import com.pixeldweller.maskerade.scan.CommonWords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VeilEngineTest {

    private final VeilEngine engine = VeilEngine.of(List.of(
            Term.of("Falkenrath Logistik", "Nordwind Handel", TermKind.COMPANY),
            Term.of("Kranich", "Reiher", TermKind.PROJECT),
            Term.of("j.wessling@falkenrath-logistik.de", "a.berger@example.org", TermKind.MAIL),
            Term.of("build01.falkenrath.lan", "host12.basalt.example", TermKind.HOST),
            Term.of("FLK-LIVE-0000-DUMMY", "QZV-HJRT-4821-ABCDE", TermKind.SECRET)), CommonWords.builtIn());

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "FalkenrathLogistik, NordwindHandel",
            "falkenrathLogistik, nordwindHandel",
            "falkenrath_logistik, nordwind_handel",
            "FALKENRATH_LOGISTIK, NORDWIND_HANDEL",
            "falkenrath-logistik, nordwind-handel",
            "falkenrath.logistik, nordwind.handel",
            "falkenrathlogistik, nordwindhandel",
            "FALKENRATHLOGISTIK, NORDWINDHANDEL",
            "Falkenrath Logistik, Nordwind Handel",
            "falkenrath logistik, nordwind handel",
            "Falkenrath-Logistik, Nordwind-Handel",
            "FalkenrathTourPlanner, NordwindTourPlanner",
            "FALKENRATH_API_URL, NORDWIND_API_URL",
            "falkenrathId, nordwindId",
            "KranichApplication, ReiherApplication",
            "kranich_touren, reiher_touren",
            "/api/kranich/touren, /api/reiher/touren",
            "de.falkenrath.kranich.export.CsvExporter, de.nordwind.reiher.export.CsvExporter",
            "XMLKranichParser, XMLReiherParser",
            "kranich2go, reiher2go",
    })
    void replacesEverySpellingInTheSameForm(String real, String veiled) {
        assertEquals(veiled, engine.veil(real));
        assertEquals(real, engine.unveil(veiled));
    }

    @Test
    void replacesOnlyAtIdentifierBoundaries() {
        assertEquals("NordwindTourPlanner", engine.veil("FalkenrathTourPlanner"));
        assertEquals("Nichtfalkenrathig", engine.veil("Nichtfalkenrathig"));
        assertEquals("Kranichs", engine.veil("Kranichs"));
        assertEquals("NichtNordwind", engine.veil("NichtFalkenrath"));
    }

    @Test
    void everydayWordsOfATermAreNotReplacedAlone() {
        // "Logistik" is part of the company term, but on its own an everyday word
        assertEquals("Logistik und Lager", engine.veil("Logistik und Lager"));
        assertEquals("Nordwind Handel GmbH", engine.veil("Falkenrath Logistik GmbH"));
    }

    @Test
    void literalTermsKeepTheirCase() {
        assertEquals("mail a.berger@example.org!", engine.veil("mail j.wessling@falkenrath-logistik.de!"));
        assertEquals("ssh git@host12.basalt.example", engine.veil("ssh git@build01.falkenrath.lan"));
        assertEquals("HOST12.BASALT.EXAMPLE", engine.veil("BUILD01.FALKENRATH.LAN"));
        // secrets are exact
        assertEquals("key=QZV-HJRT-4821-ABCDE", engine.veil("key=FLK-LIVE-0000-DUMMY"));
        assertEquals("key=flk-live-0000-dummy", engine.veil("key=flk-live-0000-dummy"));
    }

    @Test
    void ambiguousSpellingsAreLeftAlone() {
        assertEquals("fAlKeNrAtH", engine.veil("fAlKeNrAtH"));
    }

    @Test
    void newIdentifiersWithPlaceholdersGoBack() {
        assertEquals("FalkenrathExportHelper", engine.unveil("NordwindExportHelper"));
        assertEquals("// exports tours for Falkenrath Logistik", engine.unveil("// exports tours for Nordwind Handel"));
        assertEquals("KRANICH_EXPORT_DIR", engine.unveil("REIHER_EXPORT_DIR"));
        assertEquals("kranichExport", engine.unveil("reiherExport"));
    }

    @Test
    void pathsAreRenamedAndRestored() {
        var path = "src/main/java/de/falkenrath/kranich/tour/FalkenrathTourPlanner.java";
        var veiled = engine.veilPath(path);
        assertEquals("src/main/java/de/nordwind/reiher/tour/NordwindTourPlanner.java", veiled);
        assertEquals(path, engine.unveilPath(veiled));
    }

    @Test
    void spellingsPreviewListsAllForms() {
        var pairs = Spellings.of(Term.of("Falkenrath Logistik", "Nordwind Handel", TermKind.COMPANY), CommonWords.builtIn());
        var reals = pairs.stream().map(Spellings.Pair::real).toList();
        assertTrue(reals.containsAll(List.of("FalkenrathLogistik", "falkenrathLogistik", "falkenrath_logistik",
                "FALKENRATH_LOGISTIK", "falkenrath-logistik", "falkenrath.logistik", "falkenrathlogistik",
                "Falkenrath Logistik", "Falkenrath")), reals.toString());
        assertFalse(reals.contains("Logistik"));
        for (var p : pairs) assertEquals(p.placeholder(), engine.veil(p.real()), p.style());
    }

    @Test
    void validatorRejectsUnusablePlaceholders() {
        assertFalse(TermValidator.problemsOf(Term.of("Falkenrath Logistik", "Seewald", TermKind.COMPANY)).isEmpty());
        assertFalse(TermValidator.problemsOf(Term.of("Kranich", "Reiher2", TermKind.PROJECT)).isEmpty());
        assertFalse(TermValidator.problemsOf(Term.of("FLK-1234", "ABC", TermKind.SECRET)).isEmpty());
        assertTrue(TermValidator.problemsOf(Term.of("Kranich", "Reiher", TermKind.PROJECT)).isEmpty());
        var list = List.of(Term.of("Kranich", "Reiher", TermKind.PROJECT), Term.of("Falke", "Reiher", TermKind.OTHER));
        assertFalse(TermValidator.listProblems(list).isEmpty());
    }
}
