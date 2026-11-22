package com.pixeldweller.maskerade.ui;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.pixeldweller.maskerade.mirror.ide.MirrorService;
import org.jetbrains.annotations.NotNull;

/** Menu actions (Tools → MaskeRade, Find Action). Each one delegates to the tool window or the mirror service. */
public final class MaskeRadeActions {

    private MaskeRadeActions() {}

    abstract static class Base extends DumbAwareAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            var project = e.getProject();
            e.getPresentation().setEnabled(project != null && enabled(project));
        }

        boolean enabled(Project project) {
            return true;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (e.getProject() != null) run(e.getProject());
        }

        abstract void run(Project project);
    }

    abstract static class WithMirror extends Base {
        @Override
        boolean enabled(Project project) {
            var service = MirrorService.getInstance(project);
            return service.hasMirror() && !service.isBusy();
        }
    }

    public static final class Scan extends Base {
        @Override
        void run(Project project) {
            MaskeRadeToolWindowFactory.showTab(project, MaskeRadeToolWindowFactory.SCANNER, ScannerPanel.class, ScannerPanel::runScan);
        }
    }

    public static final class ShowTerms extends Base {
        @Override
        void run(Project project) {
            MaskeRadeToolWindowFactory.showTab(project, MaskeRadeToolWindowFactory.TERMS, TermsPanel.class, p -> {});
        }
    }

    public static final class CreateMirror extends Base {
        @Override
        boolean enabled(Project project) {
            return !MirrorService.getInstance(project).isBusy();
        }

        @Override
        void run(Project project) {
            MaskeRadeToolWindowFactory.showTab(project, MaskeRadeToolWindowFactory.MIRROR, MirrorPanel.class, p -> {});
            MirrorService.getInstance(project).createMirror();
        }
    }

    public static final class Unveil extends WithMirror {
        @Override
        void run(Project project) {
            MirrorService.getInstance(project).unveil();
        }
    }

    public static final class RefreshBaseline extends WithMirror {
        @Override
        void run(Project project) {
            MirrorService.getInstance(project).createMirror();
        }
    }

    public static final class DeleteMirror extends WithMirror {
        @Override
        void run(Project project) {
            MirrorService.getInstance(project).deleteMirror();
        }
    }

    public static final class OpenTerminal extends WithMirror {
        @Override
        void run(Project project) {
            MirrorService.getInstance(project).openInTerminal();
        }
    }

    public static final class CompareFolders extends Base {
        @Override
        void run(Project project) {
            new com.pixeldweller.maskerade.mirror.ide.FolderDiffDialog(project).show();
        }
    }

    public static final class ExportPlainCompact extends Base {
        @Override
        void run(Project project) {
            MirrorService.getInstance(project).exportPlainCompact();
        }
    }

    public static final class OpenExplorer extends WithMirror {
        @Override
        void run(Project project) {
            MirrorService.getInstance(project).openInExplorer();
        }
    }
}
