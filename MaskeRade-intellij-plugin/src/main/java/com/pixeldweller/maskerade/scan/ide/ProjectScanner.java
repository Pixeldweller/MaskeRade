package com.pixeldweller.maskerade.scan.ide;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.pixeldweller.maskerade.model.Candidate;
import com.pixeldweller.maskerade.scan.CandidateScanner;
import com.pixeldweller.maskerade.scan.CommonWords;
import com.pixeldweller.maskerade.settings.MaskeRadeSettings;
import com.pixeldweller.maskerade.settings.TermStore;
import com.pixeldweller.maskerade.util.Globs;
import com.pixeldweller.maskerade.util.Processes;
import com.pixeldweller.maskerade.util.TextCodec;
import com.pixeldweller.maskerade.util.Words;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Collects what the pure {@link CandidateScanner} needs from the IDE: the project's text files, methods that
 * override library methods (PSI), the vocabulary of the JDK and the libraries, and context (git, modules, OS user).
 *
 * <p>Runs in a background task. All read access happens in short read actions — one per file or library root —
 * so a write action (indexing, VFS refresh) never throws away more than one small step, and nothing waits for
 * indexing: in dumb mode the PSI step is skipped and the {@code @Override} text scan covers it.
 */
public final class ProjectScanner {

    private ProjectScanner() {}

    private static final long MAX_FILE_SIZE = 2_000_000;
    private static final int MAX_LIBRARY_ENTRIES = 400_000;
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");
    /** Library vocabulary per set of class roots — libraries rarely change within a session. */
    private static final Map<String, Map<String, Integer>> VOCABULARY_CACHE = new ConcurrentHashMap<>();

    public record Result(List<Candidate> candidates, int files, int generatedSkipped, boolean psiSkipped) {}

    private record FileRef(VirtualFile file, String path, boolean test) {}

    public static Result scan(Project project, ProgressIndicator indicator) {
        var settings = MaskeRadeSettings.getInstance(project).getState();
        var store = TermStore.getInstance();
        var base = Path.of(project.getBasePath());
        indicator.setIndeterminate(false);

        // 1. which files (cheap, no contents)
        indicator.setText("MaskeRade: listing project files");
        var refs = ReadAction.nonBlocking(() -> listFiles(project, base, settings)).wrapProgress(indicator).executeSynchronously();

        // 2. contents, one short read action per file
        var documents = new ArrayList<CandidateScanner.Document>();
        for (int i = 0; i < refs.size(); i++) {
            indicator.checkCanceled();
            var ref = refs.get(i);
            indicator.setText2(ref.path());
            indicator.setFraction(0.4 * i / Math.max(1, refs.size()));
            var bytes = ReadAction.compute(() -> load(ref.file()));
            if (bytes != null && !TextCodec.isBinary(ref.file().getName(), bytes)) {
                documents.add(new CandidateScanner.Document(ref.path(), TextCodec.decode(bytes).text(), ref.test()));
            }
        }

        // 3. overriding methods without @Override (PSI) — only when the index is ready
        var overrides = new HashSet<String>();
        boolean psiSkipped = DumbService.isDumb(project);
        if (!psiSkipped) {
            indicator.setText("MaskeRade: finding methods that override library methods");
            var javaFiles = refs.stream().filter(r -> "java".equalsIgnoreCase(r.file().getExtension())).toList();
            for (int i = 0; i < javaFiles.size(); i++) {
                indicator.checkCanceled();
                indicator.setFraction(0.4 + 0.2 * i / Math.max(1, javaFiles.size()));
                var file = javaFiles.get(i).file();
                indicator.setText2(javaFiles.get(i).path());
                overrides.addAll(ReadAction.nonBlocking(() -> overridesIn(project, file)).wrapProgress(indicator).executeSynchronously());
                if (DumbService.isDumb(project)) {
                    psiSkipped = true;
                    break;
                }
            }
        }

        // 4. library vocabulary, one read action per root, cached
        indicator.setText("MaskeRade: collecting library vocabulary");
        var vocabulary = new HashMap<>(vocabulary(project, indicator));
        // the project's own package words are never "vocabulary", even if a company library shares them
        for (var doc : documents) {
            var m = PACKAGE.matcher(doc.text());
            if (m.find()) {
                for (var w : Words.letterParts(m.group(1))) vocabulary.remove(w.toLowerCase(Locale.ROOT));
            }
        }

        // 5. score
        indicator.setText("MaskeRade: scoring candidates");
        indicator.setText2("");
        var input = new CandidateScanner.Input();
        input.documents.addAll(documents);
        input.overrideNames = overrides;
        input.vocabulary = vocabulary;
        input.context = context(project, base);
        input.commonWord = CommonWords.with(settings.exceptionWordList());
        input.ignoredKeys = Set.copyOf(store.data(project).ignored);
        input.existingTerms = store.terms(project);
        input.cancelled = indicator::isCanceled;
        input.progress = f -> indicator.setFraction(0.8 + 0.2 * f);
        var candidates = CandidateScanner.scan(input);
        return new Result(candidates, documents.size(), input.generatedSkipped, psiSkipped);
    }

    private static List<FileRef> listFiles(Project project, Path base, MaskeRadeSettings.State settings) {
        // scan what goes into the mirror: with a compact mirror, hidden files are not scanned either
        var globs = Globs.forScan(settings.excludeGlobList(), settings.compactMirror);
        var index = ProjectFileIndex.getInstance(project);
        var refs = new ArrayList<FileRef>();
        index.iterateContent(file -> {
            ProgressManager.checkCanceled();
            if (file.isDirectory() || file.getLength() > MAX_FILE_SIZE || file.getFileType().isBinary()) return true;
            var rel = relative(base, file);
            if (rel != null && !globs.excludes(rel)) refs.add(new FileRef(file, rel, index.isInTestSourceContent(file)));
            return true;
        });
        return refs;
    }

    private static byte[] load(VirtualFile file) {
        try {
            return file.isValid() ? file.contentsToByteArray() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Methods that implement / override a method from outside the project but carry no {@code @Override}
     * (those with the annotation are found by the text scan already). Constructors, private and static methods
     * cannot override anything and are skipped — that keeps the expensive super-method search small.
     */
    private static Set<String> overridesIn(Project project, VirtualFile file) {
        var out = new HashSet<String>();
        if (!file.isValid() || !(PsiManager.getInstance(project).findFile(file) instanceof PsiJavaFile javaFile)) return out;
        var index = ProjectFileIndex.getInstance(project);
        for (var method : PsiTreeUtil.findChildrenOfType(javaFile, PsiMethod.class)) {
            if (method.isConstructor() || method.hasModifierProperty(PsiModifier.PRIVATE)
                    || method.hasModifierProperty(PsiModifier.STATIC) || method.hasAnnotation(CommonClassNames.JAVA_LANG_OVERRIDE)) {
                continue;
            }
            for (var sup : method.findSuperMethods()) {
                var vf = PsiUtilCore.getVirtualFile(sup);
                if (vf == null || !index.isInContent(vf)) {
                    out.add(method.getName());
                    break;
                }
            }
        }
        return out;
    }

    /** Word parts of all class and package names in the JDK and the libraries → how many names contain them. */
    private static Map<String, Integer> vocabulary(Project project, ProgressIndicator indicator) {
        var roots = ReadAction.compute(() -> OrderEnumerator.orderEntries(project).withoutModuleSourceEntries().classes().getRoots());
        var key = String.join("|", java.util.Arrays.stream(roots).map(VirtualFile::getUrl).sorted().toList());
        var cached = VOCABULARY_CACHE.get(key);
        if (cached != null) return cached;
        var counts = new HashMap<String, Integer>();
        int[] budget = {MAX_LIBRARY_ENTRIES};
        for (int i = 0; i < roots.length && budget[0] > 0; i++) {
            indicator.checkCanceled();
            indicator.setFraction(0.6 + 0.2 * i / Math.max(1, roots.length));
            var root = roots[i];
            indicator.setText2(root.getPresentableName());
            var local = new HashMap<String, Integer>();
            int[] used = {0};
            ReadAction.nonBlocking(() -> {
                local.clear();
                used[0] = 0;
                if (!root.isValid()) return null;
                VfsUtilCore.iterateChildrenRecursively(root, null, f -> {
                    ProgressManager.checkCanceled();
                    if (++used[0] > budget[0]) return false;
                    var name = f.getName();
                    if (f.isDirectory()) {
                        add(local, name);
                    } else if (name.endsWith(".class") && name.indexOf('$') < 0 && !name.startsWith("module-info")) {
                        add(local, name.substring(0, name.length() - ".class".length()));
                    }
                    return true;
                });
                return null;
            }).wrapProgress(indicator).executeSynchronously();
            budget[0] -= used[0];
            local.forEach((w, c) -> counts.merge(w, c, Integer::sum));
        }
        VOCABULARY_CACHE.put(key, counts);
        return counts;
    }

    private static void add(Map<String, Integer> counts, String name) {
        for (var w : Words.letterParts(name)) counts.merge(w.toLowerCase(Locale.ROOT), 1, Integer::sum);
    }

    private static CandidateScanner.Context context(Project project, Path base) {
        var names = new HashSet<String>();
        var mails = new HashSet<String>();
        if (Files.isDirectory(base.resolve(".git"))) {
            names.add(git(base, "config", "user.name"));
            mails.add(git(base, "config", "user.email"));
            for (var line : git(base, "log", "-n", "300", "--format=%an%n%ae").split("\\R")) {
                if (line.contains("@")) mails.add(line.strip());
                else names.add(line.strip());
            }
        }
        names.removeIf(String::isBlank);
        mails.removeIf(String::isBlank);
        var modules = ReadAction.compute(() -> {
            var out = new HashSet<String>();
            for (var m : ModuleManager.getInstance(project).getModules()) out.add(m.getName());
            return out;
        });
        return new CandidateScanner.Context(names, mails, project.getName(), modules, System.getProperty("user.name", ""));
    }

    /** Only reads — nothing of the original's git history is copied anywhere. */
    private static String git(Path dir, String... args) {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            var r = Processes.run(dir, command, Map.of("GIT_TERMINAL_PROMPT", "0"), 30);
            return r.ok() ? r.out().strip() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String relative(Path base, VirtualFile file) {
        try {
            var p = file.toNioPath();
            return p.startsWith(base) ? base.relativize(p).toString().replace('\\', '/') : null;
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }
}
