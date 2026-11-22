# MaskeRade Sample

A small Spring Boot backend to try out the **MaskeRade** IntelliJ plugin: scan it, mirror it, let an agent work in the
mirror, and unveil the changes.

> Everything in here is **made up**: the company *Falkenrath Logistik GmbH*, its internal project *Kranich*
> (tour planning), the developer *Jorinde Wessling*, the customer *Brunhild Osterkamp*, every host, address and key.
> The key in `application.properties` is visibly fake.

```
MaskeRade-sample/
├── pom.xml          Spring Boot 3.5 (BOM import), Java 21 — de.falkenrath.kranich:kranich-tourplaner
├── .run/            run configurations: package / run, both offline
└── src/
    ├── main/java/de/falkenrath/kranich/…    REST API, tour planner, exporter, …
    ├── main/resources/                      application.properties, kunden.json, banner.txt, logo.png
    └── test/java/de/falkenrath/kranich/…    JUnit 5 + MockMvc
```

## Build

| Command | What happens |
|---|---|
| `mvn -o package` | builds the jar and runs the tests (works offline) |
| `mvn -o spring-boot:run` | starts the API on http://localhost:8080/api/kranich/touren |

Both work in the original **and** in the mirror, where packages, classes, paths and texts carry placeholders.

## What the scanner should find

| Case | Example in the sample | Expected |
|---|---|---|
| Package prefix | `de.falkenrath.kranich` | candidate (company, codename); the directories are renamed in the mirror |
| Word part in identifiers | `FalkenrathTourPlanner`, `KranichApplication`, `FALKENRATH_API_URL`, `falkenrathId` | candidate; every spelling replaced in the same form |
| kebab/snake in texts | `falkenrath-logistik` in `application.properties`, `kranich_touren` as table name | replaced; the table name is reported as "external contract" |
| Persons | `@author Jorinde Wessling`, the customer in `kunden.json` and in `FalkenrathTourPlannerTest` | candidate (person / customer) |
| Mail, host, IP, JDBC URL | `j.wessling@falkenrath-logistik.de`, `build01.falkenrath.lan`, `10.20.30.40`, `jdbc:postgresql://db.falkenrath.lan:5432/kranich` | candidates with a fixed kind |
| Windows path with user | comment with `C:\Users\jwessling\Desktop\touren.xlsx` in `CustomerRepository` | candidate (user name) |
| Secret | `falkenrath.api.key=FLK-LIVE-…` | reported as secret; a dummy of the same length in the mirror |
| Reflection string | `Class.forName("de.falkenrath.kranich.export.CsvExporter")` in `ExporterFactory` | renamed with the package, the mirror keeps working |
| JSON contract | `@JsonProperty("falkenrathId")`, REST path `/api/kranich/touren` | "external contract" |
| Base64 | `CompanyNameEncodingTest`: the company name, Base64-encoded | the residue search reports it (not replaced silently) |
| Common words | `TourService`, `Route`, `Order`, `Customer`, `Stop` | **no** candidates |
| Framework overrides | `toString()`, `CommandLineRunner.run`, `Comparator.compare`, Spring annotations | never renamed |
| Collision | `Wind.NORDWIND` — enter **`Nordwind`** as placeholder for `Falkenrath` | "Check collisions" reports `Wind.java`; the file would not unveil to itself and stays out of the mirror |
| Binary file | `src/main/resources/logo.png` (16×16, the company name in a PNG text chunk) | not copied, listed, the hit in the bytes is reported |

The scanner also lists the city names of the test data with a low score (below the default minimum of 30).
The ASCII art in `banner.txt` spells the codename — no text search can see that; this is the kind of place to
check by hand before releasing a mirror.

## Try it

1. Open this folder in the sandbox IDE (`gradlew --offline runIde` in `MaskeRade-intellij-plugin`).
2. **MaskeRade → Scanner → Run Scan**, select the candidates, **Add to Veil List**.
3. **Terms**: look at the placeholders and the spelling preview, **Check Collisions**.
4. **Mirror → Create Mirror**, read the report, **Confirm Residues**, **Open in Terminal**.
5. Change something in the mirror (or let an agent do it), then **Unveil Changes…** and apply.
