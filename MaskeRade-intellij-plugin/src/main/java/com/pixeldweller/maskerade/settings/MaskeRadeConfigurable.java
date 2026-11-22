package com.pixeldweller.maskerade.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.util.Objects;

public final class MaskeRadeConfigurable implements Configurable {

    private final Project project;
    private TextFieldWithBrowseButton mirrorRoot;
    private JBTextArea excludeGlobs;
    private JSpinner minScore;
    private JBTextArea exceptionWords;
    private JBCheckBox copyBinaries;
    private JBCheckBox compactMirror;
    private JBCheckBox agentGuards;
    private JSpinner maxFileSizeKb;
    private JBCheckBox buildAfterCreate;
    private JBTextField buildCommand;

    public MaskeRadeConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public String getDisplayName() {
        return "MaskeRade";
    }

    @Override
    public JComponent createComponent() {
        mirrorRoot = new TextFieldWithBrowseButton();
        mirrorRoot.addBrowseFolderListener(project,
                FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Parent Folder of the Mirror"));
        excludeGlobs = new JBTextArea(4, 40);
        minScore = new JSpinner(new SpinnerNumberModel(30, 0, 200, 5));
        exceptionWords = new JBTextArea(3, 40);
        exceptionWords.setLineWrap(true);
        copyBinaries = new JBCheckBox("Copy binary files into the mirror (unchanged)");
        compactMirror = new JBCheckBox("Compact mirror: only what is needed to understand the project");
        agentGuards = new JBCheckBox("Write agent guards into the mirror (.claude/settings.json, .claude/CLAUDE.md)");
        maxFileSizeKb = new JSpinner(new SpinnerNumberModel(0, 0, 10_000_000, 256));
        buildAfterCreate = new JBCheckBox("Build the mirror after creating it (offline)");
        buildCommand = new JBTextField();

        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Mirror folder:", mirrorRoot)
                .addComponentToRightColumn(hint("The mirror is created in a sub-folder named after the project's pseudonym. "
                        + "The path must contain neither your user name nor the project name."))
                .addLabeledComponent("Exclude (globs):", new JBScrollPane(excludeGlobs))
                .addComponentToRightColumn(hint("One per line, in addition to .git, .idea, *.iml, target, build, out, node_modules, "
                        + ".gradle, .claude, logs. A pattern without '/' matches any path segment, e.g. secrets.properties or docs/**."))
                .addLabeledComponent("Skip files larger than (KB):", maxFileSizeKb)
                .addComponentToRightColumn(hint("0 = no limit. Applies to the mirror, the compact export, \"Compare Folders\" and the "
                        + "scanner; skipped files are listed in the report. The scanner never reads files above 2 MB."))
                .addLabeledComponent("Minimum score:", minScore)
                .addComponentToRightColumn(hint("Scanner candidates below this score are hidden."))
                .addLabeledComponent("Exception words:", new JBScrollPane(exceptionWords))
                .addComponentToRightColumn(hint("Never suggested by the scanner, comma- or line-separated."))
                .addComponent(copyBinaries)
                .addComponentToRightColumn(hint("Default: binary files are listed but not copied — the model does not see them."))
                .addComponent(compactMirror)
                .addComponentToRightColumn(hint("Also leaves out hidden files and folders except .gitignore (.github, .mvn, .run …), "
                        + "dist, coverage, *.class, *.jar, lock files (package-lock.json, yarn.lock …), *.min.js and *.map. "
                        + "Small and quick to export; a Maven wrapper (.mvn) is then missing in the mirror."))
                .addComponent(agentGuards)
                .addComponentToRightColumn(hint("Deny rules for the original and the IDE config, and \"work only in this folder\". "
                        + "Off: the mirror contains no .claude folder. A .claude folder of the original is never copied "
                        + "and never unveiled."))
                .addComponent(buildAfterCreate)
                .addLabeledComponent("Build command:", buildCommand)
                .addComponentToRightColumn(hint("Empty = the Maven bundled with the IDE (mvn -o -B package) or the project's "
                        + "Gradle wrapper (gradlew --offline build)."))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    private static JBLabel hint(String text) {
        return new JBLabel(text, UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
    }

    @Override
    public boolean isModified() {
        var s = state();
        return !Objects.equals(mirrorRoot.getText().trim(), s.mirrorRoot)
                || !Objects.equals(excludeGlobs.getText().trim(), s.excludeGlobs)
                || (int) minScore.getValue() != s.minScore
                || !Objects.equals(exceptionWords.getText().trim(), s.exceptionWords)
                || copyBinaries.isSelected() != s.copyBinaries
                || compactMirror.isSelected() != s.compactMirror
                || agentGuards.isSelected() != s.agentGuards
                || (int) maxFileSizeKb.getValue() != s.maxFileSizeKb
                || buildAfterCreate.isSelected() != s.buildAfterCreate
                || !Objects.equals(buildCommand.getText().trim(), s.buildCommand);
    }

    @Override
    public void apply() {
        var s = state();
        s.mirrorRoot = mirrorRoot.getText().trim();
        s.excludeGlobs = excludeGlobs.getText().trim();
        s.minScore = (int) minScore.getValue();
        s.exceptionWords = exceptionWords.getText().trim();
        s.copyBinaries = copyBinaries.isSelected();
        s.compactMirror = compactMirror.isSelected();
        s.agentGuards = agentGuards.isSelected();
        s.maxFileSizeKb = (int) maxFileSizeKb.getValue();
        s.buildAfterCreate = buildAfterCreate.isSelected();
        s.buildCommand = buildCommand.getText().trim();
    }

    @Override
    public void reset() {
        var s = state();
        mirrorRoot.setText(s.mirrorRoot);
        excludeGlobs.setText(s.excludeGlobs);
        minScore.setValue(s.minScore);
        exceptionWords.setText(s.exceptionWords);
        copyBinaries.setSelected(s.copyBinaries);
        compactMirror.setSelected(s.compactMirror);
        agentGuards.setSelected(s.agentGuards);
        maxFileSizeKb.setValue(s.maxFileSizeKb);
        buildAfterCreate.setSelected(s.buildAfterCreate);
        buildCommand.setText(s.buildCommand);
    }

    private MaskeRadeSettings.State state() {
        return MaskeRadeSettings.getInstance(project).getState();
    }
}
