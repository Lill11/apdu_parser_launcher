import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

public class UILayoutSelfTest {

    public static void main(String[] args) throws Exception {
        final Throwable[] failure = new Throwable[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                runChecks();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });

        if (failure[0] != null) {
            throw new RuntimeException(failure[0]);
        }

        System.out.println("UI_LAYOUT_SELF_TEST=PASS");
    }

    private static void runChecks() throws Exception {
        Constructor<ApduParserLauncherUI> ctor = ApduParserLauncherUI.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ApduParserLauncherUI ui = ctor.newInstance();

        Field frameField = ApduParserLauncherUI.class.getDeclaredField("frame");
        frameField.setAccessible(true);
        JFrame frame = (JFrame) frameField.get(ui);
        frame.setVisible(true);

        try {
            assertWindowSize(ui, frame, 980, 760);
            assertWindowSize(ui, frame, 1280, 900);
            assertWindowSize(ui, frame, 1600, 1080);
            assertRegisterDialog(ui);
        } finally {
            frame.dispose();
        }
    }

    private static void assertWindowSize(ApduParserLauncherUI ui, JFrame frame, int width, int height) throws Exception {
        frame.setSize(width, height);
        frame.doLayout();
        frame.validate();

        JButton removeSelected = (JButton) getField(ui, "removeSelectedButton");
        JButton clearAll = (JButton) getField(ui, "clearAllButton");
        JButton parseLogs = (JButton) getField(ui, "parseButton");
        JButton toggleConsole = (JButton) getField(ui, "toggleConsoleButton");
        JScrollPane importedLogsScrollPane = (JScrollPane) getField(ui, "importedLogsScrollPane");
        Container responsiveColumns = (Container) getField(ui, "responsiveColumnsPanel");
        Container consoleCard = (Container) getField(ui, "consoleCardPanel");
        JTabbedPane filterTabs = (JTabbedPane) getField(ui, "filterTabs");
        JTabbedPane outputTabs = (JTabbedPane) getField(ui, "outputTabs");

        ensureComponentVisible(removeSelected, "Remove Selected button");
        ensureComponentVisible(clearAll, "Clear All button");
        ensureComponentVisible(parseLogs, "Parse Logs button");
        ensureComponentVisible(toggleConsole, "Show Console button");
        ensureComponentVisible(importedLogsScrollPane, "Imported Logs scroll area");
        ensureComponentVisible(responsiveColumns, "Responsive columns panel");
        ensureComponentVisible(filterTabs, "Analysis filter tabs");
        ensureComponentVisible(outputTabs, "Output tabs");
        if (consoleCard.isVisible()) {
            throw new IllegalStateException("Processing console should be collapsed by default");
        }

        if (removeSelected.getWidth() < removeSelected.getMinimumSize().width) {
            throw new IllegalStateException("Remove Selected button width is below its minimum size at " + width + "x" + height);
        }
        if (clearAll.getWidth() < clearAll.getMinimumSize().width) {
            throw new IllegalStateException("Clear All button width is below its minimum size at " + width + "x" + height);
        }
        assertHasTab(filterTabs, "ALL");
        assertHasTab(filterTabs, "ES10");
        assertHasTab(filterTabs, "FETCH/TR");
        assertHasTab(filterTabs, "LSI");
    }

    private static void assertRegisterDialog(ApduParserLauncherUI ui) throws Exception {
        Method buildDialog = ApduParserLauncherUI.class.getDeclaredMethod("buildRegisterLogTypeDialog", ApduParserEngine.ParserDefinition.class);
        buildDialog.setAccessible(true);
        JDialog dialog = (JDialog) buildDialog.invoke(ui, new Object[] { null });
        try {
            if (!dialog.isResizable()) {
                throw new IllegalStateException("Register dialog is not resizable");
            }
            Dimension min = dialog.getMinimumSize();
            if (min.width < 700 || min.height < 540) {
                throw new IllegalStateException("Register dialog minimum size is too small");
            }

            dialog.setModal(false);
            dialog.setSize(760, 580);
            dialog.setVisible(true);
            dialog.doLayout();
            dialog.validate();

            List<JScrollPane> scrollPanes = findComponents(dialog, JScrollPane.class);
            if (scrollPanes.isEmpty()) {
                throw new IllegalStateException("Register dialog has no scroll pane");
            }

            List<JButton> buttons = findComponents(dialog, JButton.class);
            boolean hasSave = false;
            boolean hasCancel = false;
            for (JButton button : buttons) {
                if ("Save".equals(button.getText())) {
                    hasSave = true;
                    ensureComponentVisible(button, "Save button");
                }
                if ("Cancel".equals(button.getText())) {
                    hasCancel = true;
                    ensureComponentVisible(button, "Cancel button");
                }
            }
            if (!hasSave || !hasCancel) {
                throw new IllegalStateException("Register dialog is missing Save/Cancel buttons");
            }
        } finally {
            dialog.dispose();
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void ensureComponentVisible(Component component, String label) {
        if (component == null || component.getWidth() <= 0 || component.getHeight() <= 0 || !component.isShowing()) {
            throw new IllegalStateException(label + " is not visible");
        }
    }

    private static void assertHasTab(JTabbedPane tabs, String expectedLabel) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (expectedLabel.equals(tabs.getTitleAt(i))) {
                return;
            }
        }
        throw new IllegalStateException("Missing tab: " + expectedLabel);
    }

    private static <T> List<T> findComponents(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                found.add(type.cast(component));
            }
            if (component instanceof Container child) {
                found.addAll(findComponents(child, type));
            }
        }
        return found;
    }
}
