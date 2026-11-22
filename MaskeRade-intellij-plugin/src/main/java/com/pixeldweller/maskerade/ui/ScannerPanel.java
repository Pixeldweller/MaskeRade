package com.pixeldweller.maskerade.ui;

import com.intellij.find.FindModel;
import com.intellij.find.findInProject.FindInProjectManager;
import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.pixeldweller.maskerade.mirror.ide.MirrorService;
import com.pixeldweller.maskerade.model.Candidate;
import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.model.TermKind;
import com.pixeldweller.maskerade.scan.ide.ProjectScanner;
import com.pixeldweller.maskerade.settings.MaskeRadeSettings;
import com.pixeldweller.maskerade.settings.SeedStore;
import com.pixeldweller.maskerade.settings.TermStore;
import com.pixeldweller.maskerade.util.Words;
import com.pixeldweller.maskerade.veil.Collisions;
import com.pixeldweller.maskerade.veil.PlaceholderGenerator;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;

/** Runs the candidate scan and moves candidates into the veil list (or ignores them). */
public final class ScannerPanel extends SimpleToolWindowPanel implements Disposable {

    private final Project project;
    private final Runnable onTermsChanged;
    private final List<Candidate> all = new ArrayList<>();
    private final List<Candidate> shown = new ArrayList<>();
    private final CandidateTableModel model = new CandidateTableModel();
    private final JBTable table = new JBTable(model);
    private final ComboBox<Object> kindFilter = new ComboBox<>();
    private final JSpinner minScore;
    private final JBLabel status = new JBLabel("Run the scan to find terms worth veiling.");
    private String scanInfo = "";

    public ScannerPanel(Project project, Runnable onTermsChanged) {
        super(true, true);
        this.project = project;
        this.onTermsChanged = onTermsChanged;
        minScore = new JSpinner(new SpinnerNumberModel(MaskeRadeSettings.getInstance(project).getState().minScore, 0, 200, 5));

        table.setAutoCreateRowSorter(false);
        table.setRowSorter(new TableRowSorter<>(model));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(220));
        table.getColumnModel().getColumn(5).setPreferredWidth(JBUI.scale(420));
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(@NotNull MouseEvent event) {
                var c = selected();
                if (c.size() != 1) return false;
                showLocations(c.getFirst());
                return true;
            }
        }.installOn(table);

        kindFilter.addItem("All kinds");
        for (var k : TermKind.values()) kindFilter.addItem(k);
        kindFilter.addActionListener(e -> applyFilter());
        minScore.addChangeListener(e -> applyFilter());

        var group = new DefaultActionGroup();
        group.add(DumbAwareAction.create("Run Scan", AllIcons.Actions.Refresh, e -> runScan()));
        group.add(new SelectionAction("Add to Veil List", AllIcons.General.Add, this::addToVeilList));
        group.add(new SelectionAction("Ignore", AllIcons.Actions.Cancel, this::ignore));
        group.add(new SelectionAction("Show Locations", AllIcons.Actions.Find, list -> showLocations(list.getFirst())));
        var toolbar = ActionManager.getInstance().createActionToolbar("MaskeRadeScanner", group, true);
        toolbar.setTargetComponent(this);

        var filters = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        filters.add(toolbar.getComponent());
        filters.add(new JBLabel("Kind:"));
        filters.add(kindFilter);
        filters.add(new JBLabel("Min. score:"));
        filters.add(minScore);
        setToolbar(filters);

        var content = new JPanel(new BorderLayout());
        content.add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER);
        status.setBorder(JBUI.Borders.empty(4, 8));
        content.add(status, BorderLayout.SOUTH);
        setContent(content);
    }

    public void runScan() {
        status.setText("Scanning…");
        new Task.Backgroundable(project, "MaskeRade: scanning for terms worth veiling", true) {
            ProjectScanner.Result result;
            long started;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                started = System.currentTimeMillis();
                result = ProjectScanner.scan(project, indicator);
            }

            @Override
            public void onSuccess() {
                all.clear();
                all.addAll(result.candidates());
                scanInfo = result.files() + " files in " + (System.currentTimeMillis() - started) / 1000 + " s"
                        + (result.generatedSkipped() > 0 ? ", " + result.generatedSkipped() + " minified/generated (patterns only)" : "")
                        + (result.psiSkipped() ? ", indexing was running: overrides via @Override only" : "");
                applyFilter();
            }

            @Override
            public void onCancel() {
                status.setText("Scan cancelled.");
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                status.setText("Scan failed: " + MirrorService.describe(error));
            }
        }.queue();
    }

    private void applyFilter() {
        int min = (int) minScore.getValue();
        var kind = kindFilter.getSelectedItem() instanceof TermKind k ? k : null;
        shown.clear();
        for (var c : all) {
            if (c.score() >= min && (kind == null || c.kind() == kind)) shown.add(c);
        }
        model.fireTableDataChanged();
        long secrets = all.stream().filter(Candidate::secret).count();
        status.setText(shown.size() + " of " + all.size() + " candidates shown"
                + (secrets > 0 ? " — " + secrets + " secret(s): they do not belong into the repository" : "")
                + (scanInfo.isEmpty() ? "" : "   ·   " + scanInfo));
    }

    private List<Candidate> selected() {
        var out = new ArrayList<Candidate>();
        for (int view : table.getSelectedRows()) out.add(shown.get(table.convertRowIndexToModel(view)));
        return out;
    }

    private void showLocations(Candidate c) {
        var find = new FindModel();
        find.setStringToFind(c.term());
        find.setCaseSensitive(c.secret());
        find.setWholeWordsOnly(false);
        find.setRegularExpressions(false);
        find.setProjectScope(true);
        // double-click is a plain Swing event: no write-intent lock on the EDT (IntelliJ 2024.x+)
        com.intellij.openapi.application.WriteIntentReadAction.run((Runnable) () -> FindInProjectManager.getInstance(project).startFindInProject(find));
    }

    private void addToVeilList(List<Candidate> candidates) {
        new Task.Backgroundable(project, "MaskeRade: adding terms", true) {
            final List<Term> added = new ArrayList<>();

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                var generator = new PlaceholderGenerator(SeedStore.seed(project));
                indicator.setText("MaskeRade: reading the project for collision checks");
                var occurrences = new ProjectTexts.Occurrences(ProjectTexts.collect(project));
                var store = TermStore.getInstance();
                var terms = new ArrayList<>(store.terms(project));
                var used = new HashSet<String>();
                var keys = new HashSet<String>();
                for (var t : terms) {
                    used.add(Words.key(t.placeholder));
                    used.add(Words.key(t.real));
                    keys.add(t.key());
                }
                for (var c : candidates) {
                    var term = new Term(c.term(), "", c.kind());
                    if (!keys.add(term.key())) continue;
                    Predicate<String> taken = p -> used.contains(Words.key(p)) || occurrences.contains(p)
                            || TermsPanel.prefixClash(p, used);
                    term.placeholder = generator.suggest(c.kind(), c.term(), taken);
                    term.note = c.secret() ? "secret — remove it from the repository" : c.contract() ? "external contract" : "";
                    used.add(Words.key(term.placeholder));
                    terms.add(term);
                    added.add(term);
                }
                store.setTerms(project, terms);
            }

            @Override
            public void onSuccess() {
                all.removeAll(candidates);
                applyFilter();
                onTermsChanged.run();
                MirrorService.getInstance(project).notifyUser("Added to the veil list", added.size() + " term(s) — check the "
                        + "placeholders in the Terms tab", NotificationType.INFORMATION);
            }
        }.queue();
    }

    private void ignore(List<Candidate> candidates) {
        var ignored = TermStore.getInstance().data(project).ignored;
        for (var c : candidates) {
            if (!ignored.contains(c.key())) ignored.add(c.key());
        }
        all.removeAll(candidates);
        applyFilter();
    }

    @Override
    public void dispose() {
    }

    private final class SelectionAction extends DumbAwareAction {
        private final java.util.function.Consumer<List<Candidate>> handler;

        SelectionAction(String text, Icon icon, java.util.function.Consumer<List<Candidate>> handler) {
            super(text, null, icon);
            this.handler = handler;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(table.getSelectedRowCount() > 0);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            var list = selected();
            if (!list.isEmpty()) handler.accept(list);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class CandidateTableModel extends AbstractTableModel {
        private final String[] columns = {"Term", "Kind", "Score", "Count", "Files", "Reason"};

        @Override
        public int getRowCount() {
            return shown.size();
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
            return column >= 2 && column <= 4 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            var c = shown.get(row);
            return switch (column) {
                case 0 -> c.secret() ? "🔑 " + c.term() : c.term();
                case 1 -> c.kind().label();
                case 2 -> c.score();
                case 3 -> c.count();
                case 4 -> c.files();
                default -> c.reasonText();
            };
        }
    }
}
