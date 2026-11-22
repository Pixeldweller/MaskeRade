# MaskeRade

IntelliJ IDEA plugin that **pseudonymizes a project before an AI agent works on it** and turns the agent's changes
**back into the real names** afterwards.

> **Version:** 0.2 — targeting IntelliJ IDEA 2025.3+

The agent (Claude Code, Codex, …) never sees the real names — not in code, comments, file and package names,
paths, build files or test data. MaskeRade works one step earlier than a proxy that replaces names in requests:
the model only ever gets a pseudonymized **mirror** of the project.

## How it works

1. **Veil** — MaskeRade creates a mirror of the project in its own folder (default `C:\veiled\<pseudonym>`).
   Contents *and* paths are pseudonymized, the mirror gets a fresh `git init` with a commit "baseline".
   The original's history is not copied.
2. **Work** — start the agent in the mirror. It only sees placeholders, and the mirror builds and its tests run.
3. **Unveil** — MaskeRade diffs the mirror against the baseline, translates every change back (contents, paths,
   new and renamed files) and shows it in IntelliJ's diff. Apply file by file or hunk by hunk as **one undoable
   command**, shelve it, or **save it as a `.patch` file** (unified diff against the baseline, relative to the
   project root — *VCS → Apply Patch…* or `git apply`). If the original changed in the meantime, the three-way
   merge opens.
4. Refresh the baseline (original → new mirror) or delete the mirror.

The original is never changed while the agent works, so git history and IDE indexes stay untouched.

**Compact mirror** (Settings → Tools → MaskeRade): only what is needed to understand the project — hidden files and
folders are left out except `.gitignore` (so no `.git`, `.github`, `.mvn`, `.run` …), as are `dist`, `coverage`,
`*.class`, `*.jar`, lock files (`package-lock.json`, `yarn.lock` …), `*.min.js` and `*.map`. Small and quick to
export. Unveiling still finds hidden files the agent creates — they are listed, but not selected by default.

**Agent guards** can be switched off in the settings; the mirror then contains no `.claude` folder at all.
A `.claude` folder of the original is never copied and never unveiled.

**Export Compact Copy (no veil)…** writes the same compact selection *without any renaming*, without `.git` and
`.claude`, into a new sub-folder `<project>-compact` — for handing a project over as it is.

## Compare folders → patch

*Tools → MaskeRade → Compare Folders → Patch…* (or the Mirror tab) diffs **any two folders** — original ↔ mirror,
two originals, or the original ↔ a veiled export that was changed somewhere else and came back as a plain folder
(no git baseline needed). Either side can be marked as veiled; it is unveiled first (contents and paths), so the
patch speaks real names. Real terms found in a veiled side are reported (leak). Files missing on the right can be
ignored (exports leave binary / compact files out).

Every differing file has a checkbox — only the selected ones go into the patch; hidden and generated files
(`.idea`, `.claude`, `target` …) are listed but not selected. When applying, *VCS → Apply Patch…* lets you
deselect files again, and `git apply --exclude=<glob>` skips them on the command line.

The result is a git-style unified diff: `git apply` or *VCS → Apply Patch…* in the left folder. Line endings are
written as LF by default — what git does with `core.autocrlf=true` (Git for Windows default) and what IntelliJ
expects; tick *Exact line endings* for `core.autocrlf=false`. Binary files are listed in a note at the top of the
patch, not in it.

## Veil list (Terms tab)

A term is *real → placeholder*, a kind (person, company, project/codename, domain/host, mail, customer, product,
user name, secret, other), active yes/no and a note. Spellings are derived automatically and replaced **in the same
form**:

| Spelling | Real | Veiled |
|---|---|---|
| PascalCase / camelCase | `FalkenrathLogistik`, `falkenrathLogistik` | `NordwindHandel`, `nordwindHandel` |
| snake / SCREAMING | `falkenrath_logistik`, `FALKENRATH_LOGISTIK` | `nordwind_handel`, `NORDWIND_HANDEL` |
| kebab / dot / flat | `falkenrath-logistik`, `falkenrath.logistik`, `falkenrathlogistik` | `nordwind-handel`, … |
| Words | "Falkenrath Logistik" | "Nordwind Handel" |
| Word part in identifiers | `FalkenrathTourPlanner` | `NordwindTourPlanner` |

Replacements happen only at identifier boundaries (camel humps, `_ - . /`, white space, digits): `Falkenrath` in
`FalkenrathTourPlanner` yes, in `Nichtfalkenrathig` no. Mail addresses, hosts and secrets are replaced literally.

- **Suggest Placeholder** picks made-up names per kind from built-in word lists (persons, companies, codenames,
  `*.example` hosts, `@example.org` mails, same-shape dummies for secrets), deterministically from a per-project
  seed kept in the IDE's PasswordSafe. Suggestions never occur in the original.
- **Import / Export** use the agent proxy's format (`real = placeholder`, `#` comments); the export lists every
  spelling on its own line.
- The list is stored **outside the project** in the IDE config folder (`options/maskerade-terms.xml`, entries
  kept apart by a hash of the project path), so the agent in the mirror cannot read it.

## Scanner

| Source | Examples |
|---|---|
| Identifiers | package names, classes, methods, fields, constants, split into word parts |
| Texts | string literals, comments, Javadoc (`@author`), properties, YAML, JSON, XML, Markdown, SQL, build files |
| Fixed kinds | mail addresses, hosts (`.lan`, `.local`, `.intern`, `.corp` …), IPs (private ones higher), JDBC URLs, user names in Windows / Unix home paths, IBANs, phone numbers |
| Secrets | `sk-…`, `ghp_…`, `AKIA…`, `xox…`, private keys, high-entropy values assigned to `password`, `secret`, `token`, `key` — reported ("does not belong into the repository") and veiled with a same-length dummy |
| Context | `git config user.name/email`, authors from `git log` (read only), project and module names, OS user name |

Not a candidate: words from the JDK and the libraries on the classpath (collected during the scan), a built-in list
of a few thousand everyday English/German words and programming terms (plus your own exceptions), and methods that
override or implement library methods (`toString`, `run`, `compare` …).

The score rises for words that occur in many files, as a package prefix, next to `@author` / `GmbH` / `Ltd` /
"customer", capitalized in prose, or matching a git author or the project name; it drops for short words and words
only found in tests. Double-click opens the locations in the Find tool window; select candidates and
**Add to Veil List** or **Ignore**.

## Correctness checks

| Check | Rule |
|---|---|
| **Reversibility** | every file must satisfy `unveil(veil(original)) == original` byte for byte (line endings, BOM, encoding); otherwise it stays out of the mirror and is listed with the reason (usually a **collision**: the placeholder already occurs in the original) |
| **Residues** | the finished mirror (contents and paths) is searched for every real term — any case, with separators between the letters (`f-a-l-k-e-n-r-a-t-h`), Base64, `\u`-escaped, UTF-16 in binary data. The mirror is released only when the list is empty or every hit is confirmed |
| **Consistent renaming** | package directory and `package` line, class and file name, imports, XML / properties / reflection strings — all with the same placeholder; optionally the mirror is built offline right after it was created |
| **External contracts** | JSON field names, REST paths, tables/columns, property keys, bean names, message keys, Maven/Gradle coordinates, URLs of real servers — replaced in the mirror, but reported as "the mirror behaves differently here" |
| **Binary files** | not copied by default and listed; released files are copied unchanged, with a warning if a real term is in their bytes |
| **Unveiling** | new identifiers with placeholders (`NordwindExportHelper` → `FalkenrathExportHelper`) go back by the same rules; reported: real terms in the agent's output (leak), placeholders in spellings that do not map back, deleted / renamed files, conflicts |
| **Agent guards** | the mirror gets `.claude/settings.json` with `deny` rules for the original and the IDE config folder, and `.claude/CLAUDE.md` ("work only in this folder") |

Deterministic and offline — no model, no network inside the plugin.

## UI

- Tool window **MaskeRade** (right) with the tabs **Terms**, **Scanner**, **Mirror**.
- **Tools → MaskeRade** and *Find Action*: edit veil list, scan, create mirror, open in terminal / explorer,
  unveil, compare folders → patch, refresh baseline, delete mirror.
- **Settings → Tools → MaskeRade**: mirror folder, exclusion globs, minimum score, exception words, copy binary
  files, compact mirror, build after creating.

## Building

`gradle.properties` points `localIdePath` to the installed IDE, so the IDE itself is not downloaded:

```bash
./gradlew --offline runIde       # sandbox IDE — open ../MaskeRade-sample there
./gradlew --offline buildPlugin  # build/distributions/MaskeRade-0.2.zip
./gradlew --offline test         # core tests (veil, scan, mirror — no IntelliJ API needed)
```

Or from the root folder: `mvn package` (sample + plugin), `-DskipPlugin` for the sample only.
The core (`util`, `model`, `veil`, `scan`, `mirror`) is plain Java without IntelliJ dependencies; the IDE glue lives
in `scan.ide`, `mirror.ide`, `settings` and `ui`. The mirror needs `git` on the PATH.
