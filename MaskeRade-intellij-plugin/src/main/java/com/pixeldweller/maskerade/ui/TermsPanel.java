package com.pixeldweller.maskerade.ui;

import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.pixeldweller.maskerade.mirror.ide.MirrorService;
import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.model.TermKind;
import com.pixeldweller.maskerade.settings.SeedStore;
import com.pixeldweller.maskerade.settings.TermStore;
import com.pixeldweller.maskerade.veil.Collisions;
import com.pixeldweller.maskerade.veil.PlaceholderGenerator;
import com.pixeldweller.maskerade.veil.ProxyListFormat;
import com.pixeldweller.maskerade.veil.Spellings;
import com.pixeldweller.maskerade.veil.TermValidator;
import com.pixeldweller.maskerade.veil.VeilEngine;
import com.pixeldweller.maskerade.util.Words;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** The veil list: real term → placeholder, kind, active, note; with spelling preview, import/export and collision check. */
public final class TermsPanel extends JPanel implements Disposable {

    private final Project project;
    private final List<Term> terms = new ArrayList<>();
    private final TermTableModel model = new TermTableModel();
    private final JBTable table = new JBTable(model);
    private final JBTextArea preview = new JBTextArea();
    private final JBLabel status = new JBLabel();

    public TermsPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        terms.addAll(TermStore.getInstance().terms(project));

        table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(50));
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new ComboBox<>(TermKind.values())));
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updatePreview();
        });

        var decorator = ToolbarDecorator.createDecorator(table)
                .setAddAction(b -> addTerm())
                .setRemoveAction(b -> removeSelected())
                .disableUpDownActions()
                .addExtraAction(DumbAwareAction.create("Suggest Placeholder", AllIcons.Actions.Lightning, e -> suggestPlaceholders(selectedRows())))
                .addExtraAction(DumbAwareAction.create("Check Collisions", AllIcons.General.InspectionsEye, e -> checkCollisions()))
                .addExtraAction(DumbAwareAction.create("Import (Proxy Format)…", AllIcons.ToolbarDecorator.Import, e -> importList()))
                .addExtraAction(DumbAwareAction.create("Export (Proxy Format)…", AllIcons.ToolbarDecorator.Export, e -> exportList()));

        preview.setEditable(false);
        preview.setFont(JBUI.Fonts.create(Font.MONOSPACED, 12));
        var splitter = new OnePixelSplitter(true, 0.65f);
        splitter.setFirstComponent(decorator.createPanel());
        splitter.setSecondComponent(ScrollPaneFactory.createScrollPane(preview));
        add(splitter, BorderLayout.CENTER);
        status.setBorder(JBUI.Borders.empty(4, 8));
        add(status, BorderLayout.SOUTH);
        updatePreview();
    }

    /** Called when other tabs (Scanner) changed the list. */
    public void reload() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        terms.clear();
        terms.addAll(TermStore.getInstance().terms(project));
        model.fireTableDataChanged();
        updatePreview();
    }

    private void save() {
        TermStore.getInstance().setTerms(project, terms);
        updatePreview();
    }

    private void addTerm() {
        terms.add(new Term("", "", TermKind.OTHER));
        model.fireTableRowsInserted(terms.size() - 1, terms.size() - 1);
        int row = terms.size() - 1;
        table.setRowSelectionInterval(row, row);
        table.editCellAt(row, 1);
    }

    private void removeSelected() {
        var rows = selectedRows();
        for (int i = rows.size() - 1; i >= 0; i--) terms.remove((int) rows.get(i));
        model.fireTableDataChanged();
        save();
    }

    private List<Integer> selectedRows() {
        var out = new ArrayList<Integer>();
        for (int view : table.getSelectedRows()) out.add(table.convertRowIndexToModel(view));
        return out;
    }

    private void updatePreview() {
        var rows = selectedRows();
        var sb = new StringBuilder();
        var common = MirrorService.getInstance(project).commonWords();
        if (rows.size() == 1) {
            var t = terms.get(rows.getFirst());
            var problems = TermValidator.problemsOf(t);
            if (!problems.isEmpty()) sb.append("Not usable: ").append(String.join("; ", problems)).append("\n\n");
            sb.append(t.isLiteral() ? "Replaced literally (lower / UPPER case too):\n" : "Spellings (replaced in the same form):\n");
            for (var p : Spellings.of(t, common)) {
                sb.append(String.format("  %-18s %-36s → %s%n", p.style(), p.real(), p.placeholder()));
            }
        } else {
            sb.append("Select a term to see its spellings.");
        }
        var listProblems = TermValidator.listProblems(terms);
        if (!listProblems.isEmpty()) {
            sb.append("\n\nList problems:\n");
            listProblems.forEach(p -> sb.append("  • ").append(p).append('\n'));
        }
        preview.setText(sb.toString());
        preview.setCaretPosition(0);
        long active = terms.stream().filter(t -> t.active).count();
        status.setText(terms.size() + " terms, " + active + " active — stored outside the project (IDE config folder)");
    }

    // ── placeholders ────────────────────────────────────────────────────────────

    private void suggestPlaceholders(List<Integer> rows) {
        if (rows.isEmpty()) return;
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        new Task.Backgroundable(project, "MaskeRade: suggesting placeholders", true) {
            final Map<Integer, String> suggestions = new java.util.HashMap<>();

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                var generator = new PlaceholderGenerator(SeedStore.seed(project));
                indicator.setText("MaskeRade: reading the project for collision checks");
                var occurrences = new ProjectTexts.Occurrences(ProjectTexts.collect(project));
                var used = new HashSet<String>();
                for (var t : terms) {
                    used.add(Words.key(t.placeholder));
                    used.add(Words.key(t.real));
                }
                for (int row : rows) {
                    var t = terms.get(row);
                    if (t.real.isBlank()) continue;
                    Predicate<String> taken = p -> used.contains(Words.key(p)) || occurrences.contains(p)
                            || prefixClash(p, used);
                    var s = generator.suggest(t.kind, t.real, taken);
                    used.add(Words.key(s));
                    suggestions.put(row, s);
                }
            }

            @Override
            public void onSuccess() {
                suggestions.forEach((row, s) -> {
                    if (row < terms.size()) terms.get(row).placeholder = s;
                });
                model.fireTableDataChanged();
                save();
            }
        }.queue();
    }

    static boolean prefixClash(String placeholder, java.util.Set<String> usedKeys) {
        for (var w : Words.letterParts(placeholder)) {
            var lw = w.toLowerCase(Locale.ROOT);
            for (var key : usedKeys) {
                for (var other : key.split(" ")) {
                    if (!other.isEmpty() && !other.equals(lw) && (other.startsWith(lw) || lw.startsWith(other))) return true;
                }
            }
        }
        return false;
    }

    // ── collisions ──────────────────────────────────────────────────────────────

    private void checkCollisions() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        var snapshot = terms.stream().map(Term::copy).toList();
        new Task.Backgroundable(project, "MaskeRade: checking collisions", true) {
            final List<String> findings = new ArrayList<>();

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                var engine = VeilEngine.of(snapshot, MirrorService.getInstance(project).commonWords());
                findings.addAll(TermValidator.listProblems(snapshot));
                for (var t : snapshot) {
                    if (t.active) TermValidator.problemsOf(t).forEach(p -> findings.add(t.real + ": " + p));
                }
                for (var e : ProjectTexts.collect(project).entrySet()) {
                    indicator.checkCanceled();
                    for (var c : Collisions.find(engine, e.getKey(), e.getValue())) findings.add(c + "  — " + c.snippet());
                }
            }

            @Override
            public void onSuccess() {
                if (findings.isEmpty()) {
                    MirrorService.getInstance(project).notifyUser("No collisions", "No placeholder occurs in the original.", NotificationType.INFORMATION);
                    return;
                }
                var shown = findings.size() > 60 ? findings.subList(0, 60) : findings;
                Messages.showWarningDialog(project, "Collisions — choose other placeholders (\"Suggest Placeholder\"):\n\n"
                        + String.join("\n", shown) + (findings.size() > 60 ? "\n… " + (findings.size() - 60) + " more" : ""), "MaskeRade");
            }
        }.queue();
    }

    // ── import / export ─────────────────────────────────────────────────────────

    private void importList() {
        var file = FileChooser.chooseFile(FileChooserDescriptorFactory.singleFile()
                .withTitle("Import Veil List (real = placeholder)"), project, null);
        if (file == null) return;
        try {
            var bytes = com.intellij.openapi.application.ReadAction.compute(file::contentsToByteArray);
            var parsed = ProxyListFormat.parse(new String(bytes, StandardCharsets.UTF_8));
            var keys = new HashSet<String>();
            terms.forEach(t -> keys.add(t.key()));
            int added = 0;
            for (var t : parsed) {
                if (keys.add(t.key())) {
                    terms.add(t);
                    added++;
                }
            }
            model.fireTableDataChanged();
            save();
            MirrorService.getInstance(project).notifyUser("Imported", added + " new term(s) from " + file.getName(), NotificationType.INFORMATION);
        } catch (IOException e) {
            Messages.showErrorDialog(project, e.getMessage(), "MaskeRade");
        }
    }

    private void exportList() {
        var descriptor = new FileSaverDescriptor("Export Veil List", "Format of the agent proxy: real = placeholder, every spelling on its own line", "txt");
        var wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save("maskerade-terms.txt");
        if (wrapper == null) return;
        var target = wrapper.getFile().toPath().toAbsolutePath();
        var mirror = MirrorService.getInstance(project).mirrorPath();
        if (target.startsWith(MirrorService.getInstance(project).projectRoot()) || (mirror != null && target.startsWith(mirror))) {
            if (Messages.showYesNoDialog(project, "The list contains the real terms. Save it inside the project or the mirror anyway?",
                    "MaskeRade", Messages.getWarningIcon()) != Messages.YES) return;
        }
        try {
            Files.writeString(target, ProxyListFormat.export(terms, MirrorService.getInstance(project).commonWords()), StandardCharsets.UTF_8);
            MirrorService.getInstance(project).notifyUser("Exported", "Veil list written to " + target, NotificationType.INFORMATION);
        } catch (IOException e) {
            Messages.showErrorDialog(project, e.getMessage(), "MaskeRade");
        }
    }

    @Override
    public void dispose() {
    }

    // ── table ───────────────────────────────────────────────────────────────────

    private final class TermTableModel extends AbstractTableModel {
        private final String[] columns = {"Active", "Term", "Placeholder", "Kind", "Note"};

        @Override
        public int getRowCount() {
            return terms.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 0 -> Boolean.class;
                case 3 -> TermKind.class;
                default -> String.class;
            };
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public Object getValueAt(int row, int column) {
            var t = terms.get(row);
            return switch (column) {
                case 0 -> t.active;
                case 1 -> t.real;
                case 2 -> t.placeholder;
                case 3 -> t.kind;
                default -> t.note;
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            var t = terms.get(row);
            switch (column) {
                case 0 -> t.active = Boolean.TRUE.equals(value);
                case 1 -> t.real = String.valueOf(value).trim();
                case 2 -> t.placeholder = String.valueOf(value).trim();
                case 3 -> t.kind = value instanceof TermKind k ? k : TermKind.OTHER;
                default -> t.note = String.valueOf(value);
            }
            fireTableRowsUpdated(row, row);
            save();
        }
    }
}
