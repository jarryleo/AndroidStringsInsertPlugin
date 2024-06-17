package cn.jarryleo.insert_strings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

public class InsertStringsToolWindow implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        InsertStringsUI insertStringsUI = new InsertStringsUI(toolWindow);
        insertStringsUI.createToolWindowContent(project);
        toolWindow.getComponent().add(insertStringsUI.getRootPanel());
    }


}
