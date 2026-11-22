package com.pixeldweller.maskerade.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.pixeldweller.maskerade.mirror.ide.MirrorService;

import javax.swing.*;
import java.awt.*;

/** Status of the mirror, its buttons and the report (binary files, irreversible files, residues, contracts). */
public final class MirrorPanel extends JPanel implements Disposable {

    private final MirrorService service;
    private final JBLabel status = new JBLabel();
    private final JBLabel path = new JBLabel();
    private final JBTextArea report = new JBTextArea();
    private final JButton create = button("Create Mirror", AllIcons.Actions.Copy);
    private final JButton terminal = button("Open in Terminal", AllIcons.Debugger.Console);
    private final JButton explorer = button("Open in Explorer", AllIcons.Actions.MenuOpen);
    private final JButton unveil = button("Unveil Changes…", AllIcons.Actions.Diff);
    private final JButton baseline = button("Refresh Baseline", AllIcons.Actions.Refresh);
    private final JButton delete = button("Delete Mirror", AllIcons.Actions.GC);
    private final JButton confirm = button("Confirm Residues", AllIcons.Actions.Checked);
    private final JButton refresh = button("Refresh Status", AllIcons.Actions.Lightning);
    private final JButton compare = button("Compare Folders → Patch…", AllIcons.Vcs.Patch);
    private final JButton exportPlain = button("Export Compact Copy (no veil)…", AllIcons.ToolbarDecorator.Export);

    public MirrorPanel(Project project) {
        super(new BorderLayout());
        this.service = MirrorService.getInstance(project);

        // Swing listeners run on the EDT without the write-intent lock (IntelliJ 2024.x+); actions get it implicitly
        onClick(create, service::createMirror);
        onClick(terminal, service::openInTerminal);
        onClick(explorer, service::openInExplorer);
        onClick(unveil, service::unveil);
        onClick(baseline, service::createMirror);
        onClick(delete, service::deleteMirror);
        onClick(confirm, service::confirmResidues);
        onClick(refresh, service::refreshStatus);
        onClick(compare, () -> new com.pixeldweller.maskerade.mirror.ide.FolderDiffDialog(project).show());
        onClick(exportPlain, service::exportPlainCompact);

        status.setFont(JBUI.Fonts.label().asBold());
        path.setForeground(UIUtil.getContextHelpForeground());
        var buttons = new JPanel(new GridLayout(0, 2, JBUI.scale(4), JBUI.scale(4)));
        for (var b : new JButton[]{create, unveil, terminal, explorer, baseline, delete, confirm, refresh, compare, exportPlain}) buttons.add(b);

        var top = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        top.setBorder(JBUI.Borders.empty(8));
        var labels = new JPanel(new GridLayout(0, 1));
        labels.add(status);
        labels.add(path);
        top.add(labels, BorderLayout.NORTH);
        top.add(buttons, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        report.setEditable(false);
        report.setFont(JBUI.Fonts.create(Font.MONOSPACED, 12));
        add(ScrollPaneFactory.createScrollPane(report), BorderLayout.CENTER);

        service.addListener(this::update, this);
        update();
        service.refreshStatus();
    }

    private static void onClick(JButton button, Runnable action) {
        button.addActionListener(e -> com.intellij.openapi.application.WriteIntentReadAction.run(action));
    }

    private static JButton button(String text, Icon icon) {
        var b = new JButton(text, icon);
        b.setHorizontalAlignment(SwingConstants.LEFT);
        return b;
    }

    private void update() {
        boolean has = service.hasMirror();
        boolean busy = service.isBusy();
        status.setText("Mirror: " + service.statusText());
        path.setText(has ? service.mirrorPath().toString() : "Created in the mirror folder from the settings, under the project's pseudonym");
        create.setEnabled(!busy && !has);
        baseline.setEnabled(!busy && has);
        unveil.setEnabled(!busy && has);
        terminal.setEnabled(has && service.isReleased());
        explorer.setEnabled(has);
        delete.setEnabled(!busy && has);
        refresh.setEnabled(!busy && has);
        var last = service.lastReport();
        confirm.setEnabled(has && !service.isReleased());
        if (last != null) {
            report.setText(last.toText());
        } else if (has) {
            report.setText("No report in this session — \"Refresh Baseline\" re-creates the mirror from the original and checks it again.");
        } else {
            report.setText("""
                    1. Terms tab: build the veil list (or add candidates from the Scanner tab).
                    2. Create Mirror: a pseudonymized copy with a fresh git baseline; the original is never touched.
                    3. Check the report below — confirm residues, look at binary files and external contracts.
                    4. Open in Terminal and start the agent (Claude Code, Codex, …) in the mirror.
                    5. Unveil Changes…: the agent's changes with real names, applied file by file or hunk by hunk.
                    """);
        }
        report.setCaretPosition(0);
    }

    @Override
    public void dispose() {
    }
}
