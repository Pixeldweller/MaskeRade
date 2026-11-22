package com.pixeldweller.maskerade.scan.ide;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
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
import java.util.regex.Pattern;

/**
 * Collects what the pure {@link CandidateScanner} needs from the IDE: the project's text files, methods that
 * override library methods (PSI), the vocabulary of the JDK and the libraries, and context (git, modules, OS user).
 * Runs in a background task; read access through non-blocking read actions.
 */
public final class ProjectScanner {

    private ProjectScanner() {}

    private static final long MAX_FILE_SIZE = 2_000_000;
    private static final int MAX_LIBRARY_ENTRIES = 400_000;
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");

    private record Collected(List<CandidateScanner.Document> documents, Set<String> overrides) {}

    public static List<Candidate> scan(Project project, ProgressIndicator indicator) {
        var settings = MaskeRadeSettings.getInstance(project).getState();
        var store = TermStore.getInstance();
        var base = Path.of(project.getBasePath());

        indicator.setText("MaskeRade: collecting project files");
        var collected = ReadAction.nonBlocking(() -> collectFiles(project, base, settings))
                .inSmartMode(project)
                .wrapProgress(indicator)
                .executeSynchronously();

        indicator.setText("MaskeRade: collecting library vocabulary");
        var vocabulary = ReadAction.nonBlocking(() -> vocabulary(project))
                .wrapProgress(indicator)
                .executeSynchronously();
        // the project's own package words are never "vocabulary", even if a company library shares them
        for (var doc : collected.documents()) {
            var m = PACKAGE.matcher(doc.text());
            if (m.find()) {
                for (var w : Words.letterParts(m.group(1))) vocabulary.remove(w.toLowerCase(Locale.ROOT));
            }
        }

        indicator.setText("MaskeRade: scoring candidates");
        var input = new CandidateScanner.Input();
        input.documents.addAll(collected.documents());
        input.overrideNames = collected.overrides();
        input.vocabulary = vocabulary;
        input.context = context(project, base);
        input.commonWord = CommonWords.with(settings.exceptionWordList());
        input.ignoredKeys = Set.copyOf(store.data(project).ignored);
        input.existingTerms = store.terms(project);
        input.cancelled = indicator::isCanceled;
        input.progress = indicator::setFraction;
        return CandidateScanner.scan(input);
    }

    private static Collected collectFiles(Project project, Path base, MaskeRadeSettings.State settings) {
        var globs = Globs.withDefaults(settings.excludeGlobList());
        var index = ProjectFileIndex.getInstance(project);
        var psiManager = PsiManager.getInstance(project);
        var documents = new ArrayList<CandidateScanner.Document>();
        var overrides = new HashSet<String>();
        index.iterateContent(file -> {
            ProgressManager.checkCanceled();
            if (file.isDirectory() || file.getLength() > MAX_FILE_SIZE) return true;
            var rel = relative(base, file);
            if (rel == null || globs.excludes(rel) || file.getFileType().isBinary()) return true;
            byte[] bytes;
            try {
                bytes = file.contentsToByteArray();
            } catch (IOException e) {
                return true;
            }
            if (TextCodec.isBinary(file.getName(), bytes)) return true;
            documents.add(new CandidateScanner.Document(rel, TextCodec.decode(bytes).text(), index.isInTestSourceContent(file)));
            if ("java".equalsIgnoreCase(file.getExtension()) && psiManager.findFile(file) instanceof PsiJavaFile javaFile) {
                collectOverrides(javaFile, index, overrides);
            }
            return true;
        });
        return new Collected(documents, overrides);
    }

    /** Methods that override or implement a method from outside the project ({@code toString}, {@code run} …). */
    private static void collectOverrides(PsiJavaFile file, ProjectFileIndex index, Set<String> overrides) {
        for (var method : PsiTreeUtil.findChildrenOfType(file, PsiMethod.class)) {
            for (var sup : method.findSuperMethods()) {
                var vf = PsiUtilCore.getVirtualFile(sup);
                if (vf == null || !index.isInContent(vf)) {
                    overrides.add(method.getName());
                    break;
                }
            }
        }
    }

    /** Word parts of all class and package names in the JDK and the libraries → how many names contain them. */
    private static Map<String, Integer> vocabulary(Project project) {
        var counts = new HashMap<String, Integer>();
        var roots = OrderEnumerator.orderEntries(project).withoutModuleSourceEntries().classes().getRoots();
        int[] budget = {MAX_LIBRARY_ENTRIES};
        for (VirtualFile root : roots) {
            VfsUtilCore.iterateChildrenRecursively(root, null, f -> {
                ProgressManager.checkCanceled();
                if (--budget[0] < 0) return false;
                var name = f.getName();
                if (f.isDirectory()) {
                    add(counts, name);
                } else if (name.endsWith(".class") && name.indexOf('$') < 0 && !name.startsWith("module-info")) {
                    add(counts, name.substring(0, name.length() - ".class".length()));
                }
                return true;
            });
        }
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
