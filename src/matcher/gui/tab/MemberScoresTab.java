package matcher.gui.tab;

import java.util.List;

import javafx.scene.control.Tab;
import javafx.scene.control.TableView;
import matcher.classifier.ClassifierResult;
import matcher.classifier.RankResult;
import matcher.gui.IGuiComponent;
import matcher.gui.ISelectionProvider;
import matcher.type.FieldInstance;
import matcher.type.MatchType;
import matcher.type.Matchable;
import matcher.type.MemberInstance;
import matcher.type.MethodInstance;

public class MemberScoresTab extends Tab implements IGuiComponent {
	public MemberScoresTab(ISelectionProvider selectionProvider) {
		super("member classifiers");

		this.selectionProvider = selectionProvider;

		init();
	}

	private void init() {
		setContent(table);
	}

	@Override
	public void onMethodSelect(MethodInstance method) {
		update();
	}

	@Override
	public void onFieldSelect(FieldInstance field) {
		update();
	}

	@SuppressWarnings("unchecked")
	private void update() {
		RankResult<? extends Matchable<?>> result = selectionProvider.getSelectedRankResult(MatchType.Method);
		if (result == null) result = selectionProvider.getSelectedRankResult(MatchType.Field);

		if (result == null) {
			table.getItems().clear();
		} else {
			table.getItems().setAll((List<ClassifierResult<MemberInstance<?>>>)(Object)result.getResults());
		}
	}

	private final ISelectionProvider selectionProvider;
	private final TableView<ClassifierResult<MemberInstance<?>>> table = ClassScoresTab.createClassifierTable();
}
