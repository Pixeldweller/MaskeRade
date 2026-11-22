# Auftrag: IntelliJ-Plugin „MaskeRade“ – Codebase verschleiern, Modell arbeiten lassen, zurückverwandeln

Baue ein IntelliJ-IDEA-Plugin, das ein Projekt **vor** der Arbeit eines KI-Modells pseudonymisiert und die Änderungen
des Modells **danach** wieder in die echten Namen zurückverwandelt. Dazu ein Scanner, der Begriffe findet, die es wert
sind, verschleiert zu werden. Lege außerdem ein Sample-Projekt an, an dem sich alles ausprobieren lässt.

Das Plugin ist eine zusätzliche Schutzschicht. Im Heimnetz läuft bereits ein Agent-Proxy, der in Anfragen an KI-Anbieter
Namen durch Platzhalter ersetzt (Liste aus Zeilen `echt = Platzhalter`). Der Proxy sieht aber nur, was über ihn geht,
und ersetzt nur Schreibweisen, die in seiner Liste stehen. Das Plugin setzt früher an: Das Modell bekommt die echten
Namen gar nicht erst zu sehen – auch keine Datei- und Paketnamen, keine Pfade, nichts in Bildern oder Base64.

---

## 1. Ordner und Tooling (genau wie `C:\Users\Derp\IdeaProjects\APIReVue`)

Lies vor dem Anfangen `C:\Users\Derp\IdeaProjects\APIReVue` (Build-Dateien, `plugin.xml`, Aufbau der Pakete,
README) und übernimm Aufbau, Versionen und Stil. Ziel: `C:\Users\Derp\IdeaProjects\entwurf\`

```
entwurf/
├── prompt.md                    diese Datei
├── pom.xml                      Root-Aggregator (Maven) – wie APIReVue/pom.xml
├── MaskeRade-intellij-plugin/   Plugin (Gradle, IntelliJ Platform Gradle Plugin)
└── MaskeRade-sample/            Beispielprojekt (Maven, Spring Boot)
```

- **Root-`pom.xml`:** Aggregator wie bei APIReVue. Modul `MaskeRade-sample`; das Plugin per `exec-maven-plugin` 3.5.0
  über den Gradle-Wrapper (`mvn clean` → `gradlew clean`, `mvn package` → `gradlew buildPlugin`), `-DskipPlugin`,
  Windows-Profil mit `gradlew.bat`. groupId und Version wie bei APIReVue (`0.1-alpha`), artifactId `maskerade-root`.
- **Plugin:** `build.gradle.kts` mit `org.jetbrains.intellij.platform` 2.10.2, `intellijIdea("2025.3.4")` bzw.
  `local(localIdePath)`, `bundledPlugin("com.intellij.java")`, `instrumentCode = localIdePath == null`,
  `sinceBuild = "252.25557"`, Java-Target 21, UTF-8, `-Xlint:deprecation`. `gradle.properties` mit
  `localIdePath=C:/Program Files/JetBrains/IntelliJ IDEA 2025.3.4` und
  `org.gradle.java.home=C:/Program Files/Java/jdk-23.0.2`, Configuration- und Build-Cache an. Gradle-Wrapper 9.5.1 –
  kopiere `gradlew`, `gradlew.bat` und `gradle/wrapper/*` aus APIReVue, nicht neu herunterladen.
  `.gitignore`, `LICENSE`, `settings.gradle.kts` (`rootProject.name = "MaskeRade"`) wie dort.
- **Paket:** `com.pixeldweller.maskerade` mit Unterpaketen wie bei APIReVue (`model`, `scan`, `veil`, `mirror`,
  `settings`, `ui`, `util`). Plugin-ID `com.pixeldweller.maskerade`, Vendor wie in APIReVue.
- **Sprache:** Code, Kommentare, UI-Texte und README auf Englisch (wie APIReVue).
- **Offline:** Die Internetverbindung ist eine getaktete Mobilverbindung. Nichts Großes herunterladen. Baue gegen die
  installierte IDE (`--offline`), im Sample nur Abhängigkeiten, die schon im lokalen Maven-Repository liegen (Spring
  Boot **3.5.16** per BOM-Import wie im APIReVue-Sample, `spring-boot-starter-web`, `-validation`, `-test`). Keine
  zusätzlichen Bibliotheken im Plugin – nur die IntelliJ Platform und das JDK. Fehlt etwas, frag nach, statt es zu laden.

---

## 2. Grundprinzip: Spiegel statt Umbau an Ort und Stelle

Das Original wird **nie** verändert, solange das Modell arbeitet. Stattdessen:

1. **Verschleiern (Veil):** Das Plugin erzeugt einen **Spiegel** des Projekts in einem eigenen Ordner (Voreinstellung
   `C:\veiled\<Pseudonym des Projekts>`, einstellbar – der Pfad darf weder Benutzernamen noch Projektnamen enthalten).
   Darin sind Dateiinhalte **und** Pfade (Ordner, Dateinamen, Paketverzeichnisse) pseudonymisiert. Der Spiegel bekommt
   ein frisches `git init` mit einem Ausgangs-Commit „baseline“, damit sich die Änderungen des Modells sauber per Diff
   ermitteln lassen. Die `.git`-Historie des Originals wird **nicht** kopiert (Autoren, Mails, alte Stände).
2. **Arbeiten:** Das Modell (Claude Code, Codex, …) wird im Spiegel gestartet. Es sieht nur Platzhalter. Der Spiegel
   muss bauen und die Tests müssen laufen – das Modell soll dort ganz normal arbeiten können.
3. **Zurückholen (Unveil):** Das Plugin ermittelt den Diff Spiegel ↔ baseline, verwandelt ihn zurück (Inhalte und
   Pfade, auch neue und umbenannte Dateien) und zeigt ihn im Diff-Dialog von IntelliJ. Der Nutzer wählt Dateien bzw.
   Hunks aus, dann wird auf das Original angewendet (als ein Undo-fähiger `WriteCommandAction`, alternativ als Patch
   in der Shelf/Changelist). Konflikte, weil das Original inzwischen geändert wurde, wie bei einem Patch anzeigen.
4. Danach kann der Spiegel aktualisiert (Original → neue baseline) oder gelöscht werden.

Warum ein Spiegel: Das Original bleibt jederzeit unversehrt, Git-Historie und IDE-Indizes werden nicht durcheinander
gebracht, und ein Fehler im Zurückverwandeln landet nie unbemerkt im echten Code.

Nebeneffekt, der ausdrücklich gewollt ist: Weil das Modell nur noch Platzhalter sieht, muss der Agent-Proxy bei diesem
Projekt nichts mehr ersetzen. Seine Prüfung („echter Name übrig?“) bleibt als Netz darunter.

---

## 3. Begriffsliste (Mapping)

- Ein Eintrag: echter Begriff → Platzhalter, Art (Person, Firma, Projektname/Codename, Domain/Host, Mailadresse,
  Kunde, Produkt, Sonstiges), aktiv ja/nein, Notiz.
- **Schreibweisen automatisch:** Aus einem Begriff (auch mehrteilig, z. B. „Falkenrath Logistik“) werden alle
  üblichen Formen abgeleitet und **in derselben Form** ersetzt: `FalkenrathLogistik`, `falkenrathLogistik`,
  `falkenrath_logistik`, `FALKENRATH_LOGISTIK`, `falkenrath-logistik`, `falkenrath.logistik`, `falkenrathlogistik`,
  „Falkenrath Logistik“, nur `Falkenrath` als Teilwort in Bezeichnern (`FalkenrathTourPlanner`). Der Platzhalter
  übernimmt Groß/klein-Muster und Trennzeichen genau. Ersetzt wird wortgenau an Bezeichner-Grenzen (CamelCase-Teile,
  `_`, `-`, `.`, `/`, Leerraum), nicht mitten in fremden Wörtern.
- **Platzhalter erzeugen:** Vorschlag pro Art aus einer eingebauten Wortliste (Personen → erfundene Vor-/Nachnamen,
  Firmen → erfundene Firmennamen, Hosts → `*.example`-Domains, Mails → `@example.org`), deterministisch aus einem
  Projekt-Seed, damit derselbe Begriff im selben Projekt immer denselben Platzhalter bekommt. Der Platzhalter darf im
  Original nicht vorkommen (sonst Kollision, siehe 6.) und kein Präfix eines anderen Platzhalters sein.
- **Import/Export im Format des Agent-Proxys:** Zeilen `echt = Platzhalter`, `#` für Kommentare. So lassen sich
  Plugin und Proxy mit derselben Liste betreiben; beim Export alle abgeleiteten Schreibweisen als eigene Zeilen
  ausgeben, weil der Proxy nur Groß/klein selbst ableitet.
- **Speicherort:** Die Liste darf **nicht** im Projekt und **nicht** im Spiegel liegen (der Agent im Spiegel soll sie
  nicht lesen können). Speichere sie anwendungsweit im IntelliJ-Konfigurationsordner (`PersistentStateComponent`,
  `@Storage` mit eigener Datei, nach Projektpfad-Hash getrennt). Der Seed in der `PasswordSafe`.

---

## 4. Scanner: Was sollte verschleiert werden?

Ein Lauf über das ganze Projekt, Ergebnis eine Kandidatenliste mit Punktzahl, Anzahl, Fundorten und vorgeschlagener
Art. Der Nutzer übernimmt Kandidaten per Klick in die Begriffsliste oder verwirft sie (verworfen merken).

**Quellen für Kandidaten**

1. **Bezeichner aus dem Projekt** (PSI, Java): Paketnamen, Klassen, Methoden, Felder, Konstanten, Enum-Werte. Jeden
   Bezeichner in Wortteile zerlegen (CamelCase, `_`, Ziffern). Kandidat ist das **Wortteil**, nicht der ganze Name.
2. **Texte:** String-Literale, Kommentare, Javadoc (`@author`!), Properties, YAML, JSON, XML, Markdown, SQL,
   `README`, Build-Dateien (`groupId`, `<developers>`, `<scm>`, `<url>`).
3. **Muster mit fester Art:** Mailadressen, Domains/Hosts (besonders `.lan`, `.local`, `.intern`, `.corp`, firmeneigene
   Domains), IP-Adressen (privat vor allem), JDBC-/Datenbank-URLs, Windows-Pfade mit Benutzernamen
   (`C:\Users\<name>\`), Unix-Home-Pfade, IBAN, Telefonnummern.
4. **Geheimnisse** (nicht pseudonymisieren, sondern melden – „gehört nicht ins Repo“): API-Schlüssel nach bekannten
   Präfixen (`sk-`, `ghp_`, `AKIA`, `xox`, `-----BEGIN … PRIVATE KEY-----`), lange Zeichenketten mit hoher Entropie in
   Zuweisungen an `password`, `secret`, `token`, `key`. Im Spiegel durch eine Dummy-Zeichenkette gleicher Länge
   ersetzen und in der Liste als „Geheimnis“ führen; beim Zurückholen nur zurückschreiben, wo sie unverändert sind.
5. **Kontext:** `git config user.name`/`user.email`, Autoren aus `git log` (nur lesen, nicht kopieren), Projekt- und
   Modulnamen, der Windows-Benutzername.

**Was kein Kandidat ist (Vokabular)**

- Alles, was aus dem **JDK oder den Bibliotheken im Klassenpfad** stammt. Statt fester Listen: Beim Scan die
  Wortteile aller Klassen-, Methoden- und Paketnamen der Bibliotheken (Module-Libraries, JDK) einsammeln und als
  „bekanntes Vokabular“ führen. Ein Wortteil, der dort oft vorkommt (`Service`, `Repository`, `Mapper`, `Request`,
  `Order`, `Tour`), ist kein Kandidat.
- Englische und deutsche Allerweltswörter und Fachbegriffe: eingebaute Wortliste (ein paar tausend häufige Wörter,
  als Ressource im Plugin, keine Downloads), ergänzbar durch eine eigene Ausnahmeliste.
- Bezeichner, die eine Bibliotheksmethode **überschreiben oder implementieren** (`toString`, `run`, `compare`,
  `configure…`) – die dürfen ohnehin nicht umbenannt werden (siehe 6.).

**Punktzahl** (Vorschlag, einstellbar): hoch für Wortteile, die nicht im Vokabular und nicht in der Wortliste stehen,
**häufig** im Projekt vorkommen (in vielen Dateien), als Paketpräfix auftauchen (`org.<name>`, `de.<firma>`), mit
Git-Autor, Mail-Domain oder Projektname übereinstimmen, groß geschrieben in Texten stehen (Eigenname) oder neben
Mustern wie `@author`, `GmbH`, `AG`, `Ltd`, `Inc`, `Kunde`, `customer` stehen. Niedrig für kurze Wortteile (< 4
Zeichen), Abkürzungen, die im Vokabular stehen, und Wortteile, die nur in Tests/Testdaten vorkommen (trotzdem zeigen).

**Ansicht:** Tabelle (Begriff, Art, Punkte, Anzahl, Dateien, Grund), Doppelklick → Fundstellen in der Find-Ansicht,
Mehrfachauswahl → „Add to veil list“ / „Ignore“. Fortschritt im Hintergrund-Task (`Task.Backgroundable`, abbrechbar,
Lesezugriff über `ReadAction.nonBlocking`).

---

## 5. Was im Spiegel landet

- **Mitkopieren (pseudonymisiert):** Quelltexte, Ressourcen, Build-Dateien, Tests, Doku, Konfiguration – alle
  Textdateien (Erkennung über `FileTypeManager` bzw. kein NUL-Byte in den ersten 8 KB).
- **Nicht mitkopieren:** `.git`, `.idea`, `*.iml`, `target/`, `build/`, `out/`, `node_modules/`, `.gradle/`, Logs,
  alles aus einer einstellbaren Ausschlussliste (Globs).
- **Binärdateien** (Bilder, PDFs, JARs, Office-Dateien): Voreinstellung **nicht kopieren** und in einer Liste zeigen
  („diese Dateien sieht das Modell nicht“). Einzeln freigeben möglich, dann ungeändert kopieren – mit Warnung, falls
  ein echter Begriff darin steht (Suche in den Bytes nach UTF-8/UTF-16 und Base64-Formen).
- Base64- und `\u`-kodierte Stellen in Textdateien: dekodieren, prüfen, bei Treffer melden (nicht still ersetzen).
- **Agent-Schranken im Spiegel:** Lege `.claude/settings.json` im Spiegel an mit `deny`-Regeln für das Lesen des
  Original-Pfads und des Plugin-Konfigurationsordners (damit der Agent nicht einfach nebenan nachsieht), und eine
  kurze `CLAUDE.md`, die sagt: „Arbeite nur in diesem Ordner.“ Den echten Namen dabei nicht hineinschreiben – Pfade
  im deny dürfen ihn enthalten, sie gehen nicht an das Modell; prüfe das trotzdem und warne, wenn doch.

---

## 6. Korrektheit – das Wichtigste

Ein Fehler beim Zurückverwandeln verändert echten Code. Deshalb:

1. **Umkehrbarkeit vor dem Herausgeben prüfen:** Für jede Datei muss `unveil(veil(original)) == original` Byte für
   Byte gelten (auch Zeilenenden, BOM, Kodierung). Datei, bei der das nicht gilt → nicht in den Spiegel, sondern in
   eine Fehlerliste mit Grund. Typische Ursache: Der Platzhalter oder eine seiner Schreibweisen steht schon im
   Original (**Kollision**) – dann einen anderen Platzhalter vorschlagen.
2. **Kein Rest im Spiegel:** Nach dem Erzeugen den ganzen Spiegel (Inhalte **und** Pfade) noch einmal nach jedem
   echten Begriff durchsuchen – locker, also auch mit Trennzeichen zwischen den Buchstaben (`f-a-l-k-e-n-r-a-t-h`),
   in Groß/klein, in Base64. Jeder Treffer wird gemeldet; „Spiegel freigeben“ erst, wenn die Liste leer oder jeder
   Treffer bewusst bestätigt ist.
3. **Der Spiegel muss bauen:** Konsistent umbenennen heißt: Paketverzeichnis und `package`-Zeile, Klassenname und
   Dateiname, Importe, Referenzen in XML/Properties/Reflection-Strings – alles mit demselben Platzhalter. Nach dem
   Erzeugen optional den Build im Spiegel starten (Maven/Gradle offline) und das Ergebnis anzeigen.
4. **Was nach außen zeigt, nicht still umbenennen:** Die Wirkung hört am Projektrand auf. Der Scanner markiert solche
   Stellen als „external contract“ und fragt pro Begriff, ob ersetzt werden soll:
   - Maven-/Gradle-Koordinaten eigener Artefakte aus einem entfernten Repository (umbenannt nicht mehr auflösbar),
   - JSON-Feldnamen (`@JsonProperty`), REST-Pfade (`@RequestMapping`), Datenbank-Tabellen/Spalten, Property-Keys,
     die von außen gesetzt werden, Bean-Namen, Message-Keys,
   - URLs zu echten Servern (im Spiegel durch `*.example` ersetzen; Tests, die sie wirklich aufrufen, laufen dann
     nicht – im Bericht sagen).
   Voreinstellung: in Texten ersetzen (das Modell soll den Namen nicht sehen), aber im Bericht als „Spiegel verhält
   sich hier anders als das Original“ führen.
5. **Zurückverwandeln neuer Inhalte:** Das Modell erfindet neue Bezeichner, die Platzhalter enthalten
   (`NordwindExportHelper`), oder schreibt Platzhalter in neue Kommentare. Unveil arbeitet auf Wortteil-Ebene mit
   denselben Schreibweisen-Regeln wie Veil, also wird daraus `FalkenrathExportHelper`. Gemeldet wird extra:
   - Platzhalter in einer Schreibweise, die nicht eindeutig zurückgeht (z. B. Groß/klein gemischt) → nachfragen,
   - ein **echter** Begriff im Diff des Modells (darf eigentlich nicht vorkommen – Hinweis auf ein Leck),
   - Dateien, die das Modell gelöscht oder umbenannt hat (Pfad zurückverwandeln, Umbenennung als solche anwenden).
6. Ausschließlich deterministisch und ohne Netz. Kein Modell, keine KI im Plugin.

---

## 7. Oberfläche (IntelliJ)

- **Tool-Window „MaskeRade“** (rechts, eigenes SVG-Icon wie bei APIReVue), drei Reiter:
  - **Terms:** Begriffsliste (Tabelle, bearbeitbar), Schreibweisen-Vorschau je Eintrag, Import/Export
    (`echt = Platzhalter`), „Check collisions“.
  - **Scanner:** Lauf starten, Kandidatentabelle (siehe 4.), Filter nach Art und Mindestpunktzahl.
  - **Mirror:** Status (kein Spiegel / bereit / Modell hat N Dateien geändert), Pfad, Buttons „Create mirror“,
    „Open in terminal“ / „Open in Explorer“, „Unveil changes…“ (Diff-Dialog, Auswahl, Anwenden), „Refresh baseline“,
    „Delete mirror“. Darunter der Bericht: übersprungene Binärdateien, Fehlerliste (nicht umkehrbar), Reste,
    „external contract“-Stellen.
- **Aktionen** auch im Menü *Tools → MaskeRade* und in *Find Action*.
- **Settings → Tools → MaskeRade** (`projectConfigurable`, wie bei APIReVue): Spiegel-Ordner, Ausschluss-Globs,
  Mindestpunktzahl, eigene Ausnahmewörter, „Binärdateien kopieren“, „Build nach dem Erzeugen“.
- Benachrichtigungen über eine eigene `notificationGroup`.
- Alles, was lange dauert, im Hintergrund mit Fortschritt und abbrechbar; keine Arbeit im EDT außer UI.

---

## 8. Tests

- Plain-JUnit-Tests (JUnit 5 aus der IntelliJ Platform bzw. `testFramework(TestFrameworkType.Platform)`; nichts
  zusätzlich laden – wenn das nicht offline geht, die Kernlogik ohne IntelliJ-Abhängigkeit halten und mit
  einfachen `main`-Tests prüfen) für die Kernlogik, die bewusst **ohne IntelliJ-API** auskommt (`veil` und `scan` als
  reine Java-Klassen):
  - Schreibweisen ableiten und in gleicher Form ersetzen (alle Formen aus 3.),
  - Wortgrenzen: `Falkenrath` in `FalkenrathTourPlanner` ja, in `Nichtfalkenrathig` nicht,
  - `unveil(veil(x)) == x` für alle Dateien des Samples, auch mit CRLF und BOM,
  - Kollision wird erkannt,
  - Pfade: Paketverzeichnis und Dateiname werden umbenannt und zurückverwandelt,
  - neue Bezeichner mit Platzhalter (`NordwindExportHelper`) gehen richtig zurück,
  - Rest-Suche findet `f_a_l_k_e_n_r_a_t_h` und die Base64-Form,
  - Scanner: Kandidaten im Sample wie in der Tabelle unten, `Service`/`Order`/`Tour` sind keine Kandidaten.
- Ein Ende-zu-Ende-Test auf dem Sample: Spiegel erzeugen → darin eine Datei ändern und eine neue anlegen (simuliertes
  Modell) → Unveil → Ergebnis gleich der erwarteten Änderung im Original.

---

## 9. Sample-Projekt `MaskeRade-sample`

Ein kleines, **frei erfundenes** Projekt einer fiktiven Firma. Keine echten Namen, Firmen, Domains oder Schlüssel
verwenden – alles erfunden, Schlüssel sichtbar unecht. Aufbau wie das APIReVue-Sample (Maven, Spring Boot 3.5.16 per
BOM-Import, Java 21, offline baubar), aber nur Backend, kein Frontend:

```
MaskeRade-sample/
├── pom.xml          groupId de.falkenrath.kranich, artifactId kranich-tourplaner
├── README.md        Erklärung + Tabelle „Was der Scanner finden soll“
├── .run/            Run-Konfigurationen wie im APIReVue-Sample (package offline, run offline)
└── src/
    ├── main/java/de/falkenrath/kranich/…
    ├── main/resources/application.properties, kunden.json, banner.txt
    └── test/java/de/falkenrath/kranich/…
```

Fiktiver Inhalt: Firma **Falkenrath Logistik GmbH**, internes Projekt mit Codename **Kranich** (Tourenplanung),
Entwicklerin **Jorinde Wessling** (`@author`, `<developers>` im POM, `j.wessling@falkenrath-logistik.de`), Kundin
**Brunhild Osterkamp** in Testdaten, interner Host `build01.falkenrath.lan`, Datenbank
`jdbc:postgresql://db.falkenrath.lan:5432/kranich`, private IP `10.20.30.40`, ein Kommentar mit
`C:\Users\jwessling\Desktop\touren.xlsx`, ein sichtbar unechter Schlüssel `FLK-LIVE-0000-DUMMY-DO-NOT-USE` in
`application.properties`.

Enthalten sein müssen diese Fälle (jeweils mit kurzem Kommentar, welcher Fall es ist):

| Fall | Beispiel im Sample | Erwartung |
|---|---|---|
| Paketpräfix | `de.falkenrath.kranich` | Kandidat (Firma, Codename), Verzeichnisse werden umbenannt |
| Wortteil in Bezeichnern | `FalkenrathTourPlanner`, `KranichApplication`, `FALKENRATH_API_URL`, `falkenrathId` | Kandidat, alle Schreibweisen gleich ersetzt |
| kebab/snake in Texten | `falkenrath-logistik` in Properties, `kranich_touren` als Tabellenname | ersetzt; Tabellenname als „external contract“ markiert |
| Personen | `@author Jorinde Wessling`, Kundin in `kunden.json` und in einem Test | Kandidat (Person) |
| Mail, Host, IP, JDBC-URL | siehe oben | Kandidat mit fester Art |
| Windows-Pfad mit Benutzer | Kommentar mit `C:\Users\jwessling\…` | Kandidat (Benutzername) |
| Geheimnis | `FLK-LIVE-…` | gemeldet als Geheimnis, im Spiegel Dummy gleicher Länge |
| Reflection-String | `Class.forName("de.falkenrath.kranich.export.CsvExporter")` | mit umbenannt, Spiegel läuft weiter |
| JSON-Vertrag | `@JsonProperty("falkenrathId")`, REST-Pfad `/api/kranich/touren` | „external contract“ |
| Base64 | Test mit `Base64`-kodiertem Firmennamen | Rest-Suche meldet ihn |
| Allerweltswörter | `TourService`, `Route`, `Order`, `Customer`, `Stop` | **kein** Kandidat |
| Framework-Overrides | `toString()`, `CommandLineRunner.run`, `Comparator.compare`, Spring-Annotationen | nicht umbenannt |
| Kollision | ein Wort im Code, das gleich einem vorgeschlagenen Platzhalter ist (im README nennen) | Kollision wird erkannt |
| Binärdatei | `src/main/resources/logo.png` (klein, selbst erzeugt, mit dem Firmennamen als Text-Chunk) | nicht kopiert, gelistet, Treffer gemeldet |

Das Sample muss mit `mvn -o package` bauen und seine Tests müssen grün sein – im Original **und** im Spiegel.

---

## 10. Vorgehen und Abschluss

1. APIReVue lesen, dann Gerüst anlegen (Root-POM, Plugin-Build, Sample), Build prüfen (`gradlew --offline buildPlugin`,
   `mvn -o package` im Sample).
2. Kernlogik (`veil`, `scan`) ohne IntelliJ-Abhängigkeit mit Tests, dann Spiegel/Unveil, dann UI.
3. README für das Plugin (wie APIReVue: was es tut, Tabelle der Prüfungen, Bauen) und für das Sample (Tabelle oben).
4. Zum Schluss: `mvn package` im Root (Plugin-ZIP unter `MaskeRade-intellij-plugin/build/distributions`),
   `gradlew --offline runIde` öffnet eine Sandbox-IDE – dort das Sample öffnen, Scanner laufen lassen, Spiegel
   erzeugen, im Spiegel eine Änderung machen, zurückholen. Berichte, was funktioniert und was nicht (mit Ausgabe).

Nicht committen und nichts veröffentlichen, solange nicht ausdrücklich darum gebeten.
