package matcher.gui.menu;

import javafx.scene.Node;
import javafx.scene.control.TabPane;
import javafx.stage.Window;

import matcher.config.ProjectConfig;

public class NewProjectTabPane extends TabPane {
	public NewProjectTabPane(ProjectConfig config, Window window, Node okButton) {
		setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
		setTabDragPolicy(TabDragPolicy.FIXED);

		init(config, window, okButton);
	}

	private void init(ProjectConfig config, Window window, Node okButton) {
		NewProjectTab matcherTab = new NewProjectTab(config, window, okButton, false);
		getTabs().add(matcherTab);

		NewProjectTab nesterTab = new NewProjectTab(config, window, okButton, true);
		getTabs().add(nesterTab);
	}

	public ProjectConfig createConfig() {
		return ((NewProjectTab)getSelectionModel().getSelectedItem()).createConfig();
	}
}
