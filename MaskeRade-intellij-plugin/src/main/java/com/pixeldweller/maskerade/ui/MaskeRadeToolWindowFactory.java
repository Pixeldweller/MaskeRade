package com.pixeldweller.maskerade.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** Tool window "MaskeRade" with the tabs Terms, Scanner and Mirror. */
public final class MaskeRadeToolWindowFactory implements ToolWindowFactory, DumbAware {

    public static final String ID = "MaskeRade";
    public static final String TERMS = "Terms";
    public static final String SCANNER = "Scanner";
    public static final String MIRROR = "Mirror";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var factory = ContentFactory.getInstance();
        var terms = new TermsPanel(project);
        var scanner = new ScannerPanel(project, terms::reload);
        var mirror = new MirrorPanel(project);

        var termsContent = factory.createContent(terms, TERMS, false);
        termsContent.setDisposer(terms);
        var scannerContent = factory.createContent(scanner, SCANNER, false);
        scannerContent.setDisposer(scanner);
        var mirrorContent = factory.createContent(mirror, MIRROR, false);
        mirrorContent.setDisposer(mirror);
        toolWindow.getContentManager().addContent(termsContent);
        toolWindow.getContentManager().addContent(scannerContent);
        toolWindow.getContentManager().addContent(mirrorContent);
    }

    /** Shows the tool window on the given tab and hands its panel to {@code then}. */
    public static <T> void showTab(Project project, String tab, Class<T> type, java.util.function.Consumer<T> then) {
        var toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID);
        if (toolWindow == null) return;
        toolWindow.activate(() -> {
            var manager = toolWindow.getContentManager();
            var content = manager.findContent(tab);
            if (content == null) return;
            manager.setSelectedContent(content);
            if (type.isInstance(content.getComponent())) then.accept(type.cast(content.getComponent()));
        });
    }
}
