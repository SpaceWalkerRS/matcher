package matcher.gui;

import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import matcher.classifier.ClassifierLevel;
import matcher.classifier.FieldClassifier;
import matcher.classifier.MethodClassifier;
import matcher.classifier.RankResult;
import matcher.classifier.nester.Nest;
import matcher.classifier.nester.NestRankResult;
import matcher.classifier.nester.NestType;
import matcher.config.Config;
import matcher.type.ClassInstance;
import matcher.type.FieldInstance;
import matcher.type.MatchType;
import matcher.type.MatchableKind;
import matcher.type.MemberInstance;
import matcher.type.MethodInstance;
import matcher.type.MethodVarInstance;

public class BottomPane extends StackPane implements IGuiComponent {
	public BottomPane(Gui gui, MatchPaneSrc srcPane, MatchPaneDst dstPane) {
		super();

		this.gui = gui;
		this.srcPane = srcPane;
		this.dstPane = dstPane;

		this.accessFlagsMenu = new AccessFlagsMenu(this.gui, this.srcPane, this.dstPane);

		init();
	}

	private void init() {
		setPadding(new Insets(GuiConstants.padding));

		getChildren().add(center);
		StackPane.setAlignment(center, Pos.CENTER);
		center.setAlignment(Pos.CENTER);

		matchButton.setText("match");
		matchButton.setOnAction(event -> match());
		matchButton.setDisable(true);

		center.getChildren().add(matchButton);

		matchableButton.setText("unmatchable");
		matchableButton.setOnAction(event -> toggleMatchable());
		matchableButton.setDisable(true);

		center.getChildren().add(matchableButton);

		matchPerfectMembersButton.setText("match 100% members");
		matchPerfectMembersButton.setOnAction(event -> matchPerfectMembers());
		matchPerfectMembersButton.setDisable(true);

		center.getChildren().add(matchPerfectMembersButton);

		addAnonymousClassButton.setText("add anonymous class");
		addAnonymousClassButton.setOnAction(event -> addAnonymousClass());
		addAnonymousClassButton.setDisable(true);

		addInnerClassButton.setText("add inner class");
		addInnerClassButton.setOnAction(event -> addInnerClass());
		addInnerClassButton.setDisable(true);

		selectedCandidateButton.setText("selected candidate: -");
		selectedCandidateButton.setOnAction(event -> toggleCandidate());
		selectedCandidateButton.setDisable(true);

		nestableButton.setText("unnestable");
		nestableButton.setOnAction(event -> toggleNestable());
		nestableButton.setDisable(true);

		getChildren().add(right);
		StackPane.setAlignment(right, Pos.CENTER_RIGHT);
		right.setAlignment(Pos.CENTER_RIGHT);
		right.setPickOnBounds(false);

		unmatchClassButton.setText("unmatch classes");
		unmatchClassButton.setOnAction(event -> unmatchClass());
		unmatchClassButton.setDisable(true);

		right.getChildren().add(unmatchClassButton);

		unmatchMemberButton.setText("unmatch members");
		unmatchMemberButton.setOnAction(event -> unmatchMember());
		unmatchMemberButton.setDisable(true);

		right.getChildren().add(unmatchMemberButton);

		unmatchVarButton.setText("unmatch vars");
		unmatchVarButton.setOnAction(event -> unmatchVar());
		unmatchVarButton.setDisable(true);

		right.getChildren().add(unmatchVarButton);

		accessFlagsMenu.setText("access");
		accessFlagsMenu.setDisable(true);

		unnestButton.setText("unnest");
		unnestButton.setOnAction(event -> unnest());
		unnestButton.setDisable(true);

		SelectListener selectListener = new SelectListener();
		srcPane.addListener(selectListener);
		dstPane.addListener(selectListener);
	}

	@Override
	public void onProjectChange() {
		boolean isNesterProject = Config.getProjectConfig().isNesterProject();
		boolean hasNesterButtons = right.getChildren().contains(unnestButton);

		if (isNesterProject && !hasNesterButtons) {
			center.getChildren().remove(matchButton);
			center.getChildren().remove(matchableButton);
			center.getChildren().remove(matchPerfectMembersButton);
			
			right.getChildren().remove(unmatchClassButton);
			right.getChildren().remove(unmatchMemberButton);
			right.getChildren().remove(unmatchVarButton);

			center.getChildren().add(addAnonymousClassButton);
			center.getChildren().add(addInnerClassButton);
			center.getChildren().add(selectedCandidateButton);
			center.getChildren().add(nestableButton);

			right.getChildren().add(accessFlagsMenu);
			right.getChildren().add(unnestButton);
		} else
		if (!isNesterProject && hasNesterButtons) {
			center.getChildren().remove(addAnonymousClassButton);
			center.getChildren().remove(addInnerClassButton);
			center.getChildren().remove(selectedCandidateButton);
			center.getChildren().remove(nestableButton);

			right.getChildren().remove(accessFlagsMenu);
			right.getChildren().remove(unnestButton);

			center.getChildren().add(matchButton);
			center.getChildren().add(matchableButton);
			center.getChildren().add(matchPerfectMembersButton);

			right.getChildren().add(unmatchClassButton);
			right.getChildren().add(unmatchMemberButton);
			right.getChildren().add(unmatchVarButton);
		}
	}

	@Override
	public void onMatchChange(Set<MatchType> types) {
		if (!types.isEmpty()) {
			updateMatchButtons();
		}
	}

	@Override
	public void onNestChange() {
		updateNestButtons();
	}

	private void updateButtons() {
		if (Config.getProjectConfig().isNesterProject()) {
			updateNestButtons();
		} else {
			updateMatchButtons();
		}
	}

	private void updateMatchButtons() {
		ClassInstance clsA = srcPane.getSelectedClass();
		ClassInstance clsB = dstPane.getSelectedClass();

		MemberInstance<?> memberA = srcPane.getSelectedMethod();
		if (memberA == null) memberA = srcPane.getSelectedField();
		MemberInstance<?> memberB = dstPane.getSelectedMethod();
		if (memberB == null) memberB = dstPane.getSelectedField();

		MethodVarInstance varA = srcPane.getSelectedMethodVar();
		MethodVarInstance varB = dstPane.getSelectedMethodVar();

		matchButton.setDisable(!canMatchClasses(clsA, clsB) && !canMatchMembers(memberA, memberB) && !canMatchVars(varA, varB));
		/*
		 * class-null   class-matchable   class-matched    member-null    member-matchable    member-matched    var-null    var-matchable    var-matched   disabled   text          target
		 * 1            x                 x                x              x                   x                 x           x                x             1          unmatchable   -
		 * 0            0                 x                x              x                   x                 x           x                x             0          matchable     cls
		 * 0            1                 0                x              x                   x                 x           x                x             0          unmatchable   cls
		 * 0            1                 1                1              x                   x                 x           x                x             1          unmatchable   -
		 * 0            1                 1                0              0                   x                 x           x                x             0          matchable     member
		 * 0            1                 1                0              1                   0                 x           x                x             0          unmatchable   member
		 * 0            1                 1                0              1                   1                 1           x                x             1          unmatchable   -
		 * 0            1                 1                0              1                   1                 0           0                x             0          matchable     var
		 * 0            1                 1                0              1                   1                 0           1                0             0          unmatchable   var
		 * 0            1                 1                0              1                   1                 0           1                1             1          unmatchable   -
		 */
		matchableButton.setDisable(clsA == null || clsA.isMatchable() && (clsA.hasMatch() || !clsA.hasPotentialMatch())
				&& (memberA == null || memberA.isMatchable() && (memberA.hasMatch() || !memberA.hasPotentialMatch())
				&& (varA == null || varA.isMatchable() && (varA.hasMatch() || !varA.hasPotentialMatch()))));
		matchableButton.setText(clsA != null && (!clsA.isMatchable()
				|| memberA != null && (!memberA.isMatchable()
						|| varA != null && !varA.isMatchable())) ? "matchable" : "unmatchable");
		unmatchClassButton.setDisable(!canUnmatchClass(clsA));
		unmatchMemberButton.setDisable(!canUnmatchMember(memberA));
		unmatchVarButton.setDisable(!canUnmatchVar(varA));

		matchPerfectMembersButton.setDisable(!canMatchPerfectMembers(clsA));
	}

	private void updateNestButtons() {
		ClassInstance clazz = srcPane.getSelectedClass();
		ClassInstance equiv = (clazz == null) ? null : clazz.equiv;

		Nest nest = (equiv == null) ? null : equiv.getNest();
		ClassInstance classNest = dstPane.getSelectedClass();
		MethodInstance methodNest = dstPane.getSelectedMethod();

		boolean hasClass = (equiv != null);
		boolean isNestable = hasClass && equiv.isNestable();
		boolean hasNest = hasClass && nest != null;
		boolean hasMethodSelected = (methodNest != null);
		boolean hasSelection = (classNest != null);

		addAnonymousClassButton.setDisable(!hasClass || hasNest || !hasSelection || !equiv.canBeAnonymous());
		addInnerClassButton.setDisable(!hasClass || hasNest || !hasSelection || !equiv.canBeInner());
		selectedCandidateButton.setText("selected candidate: " + getSelectedCandidateName(classNest, methodNest));
		selectedCandidateButton.setDisable(!hasClass || hasNest || !hasSelection || (!hasMethodSelected && !equiv.canBeAnonymous()));
		nestableButton.setText(hasClass && !hasNest && !isNestable ? "nestable" : "unnestable");
		nestableButton.setDisable(!hasClass || (hasNest && isNestable));

		accessFlagsMenu.setDisable(!hasClass || !hasNest || nest.get().getKind() == MatchableKind.METHOD);
		accessFlagsMenu.update();
		unnestButton.setDisable(!hasClass || !hasNest);
	}

	private String getSelectedCandidateName(ClassInstance clazz, MethodInstance method) {
		if (clazz == null) {
			return "-";
		}

		String name = clazz.getDisplayName(gui.getNameType(), true);

		if (method != null) {
			name += "." + method.getDisplayName(gui.getNameType(), false);
		}

		return name;
	}

	// match / unmatch actions implementation

	private boolean canMatchClasses(ClassInstance clsA, ClassInstance clsB) {
		return clsA != null && clsB != null && clsA.getMatch() != clsB && clsA.isMatchable() && clsB.isMatchable();
	}

	private boolean canMatchMembers(MemberInstance<?> memberA, MemberInstance<?> memberB) {
		return memberA != null && memberB != null && memberA.getClass() == memberB.getClass() && memberA.getMatch() != memberB && memberA.isMatchable() && memberB.isMatchable();
	}

	private boolean canMatchVars(MethodVarInstance varA, MethodVarInstance varB) {
		return varA != null && varB != null && varA.isArg() == varB.isArg() && varA.getMatch() != varB && varA.isMatchable() && varB.isMatchable();
	}

	private void match() {
		ClassInstance clsA = srcPane.getSelectedClass();
		ClassInstance clsB = dstPane.getSelectedClass();

		if (canMatchClasses(clsA, clsB)) {
			gui.getMatcher().match(clsA, clsB);
			gui.onMatchChange(EnumSet.allOf(MatchType.class));
			return;
		}

		MemberInstance<?> memberA = srcPane.getSelectedMethod();
		if (memberA == null) memberA = srcPane.getSelectedField();
		MemberInstance<?> memberB = dstPane.getSelectedMethod();
		if (memberB == null) memberB = dstPane.getSelectedField();

		if (canMatchMembers(memberA, memberB)) {
			if (memberA instanceof MethodInstance) {
				gui.getMatcher().match((MethodInstance) memberA, (MethodInstance) memberB);
				gui.onMatchChange(EnumSet.of(MatchType.Method));
			} else {
				gui.getMatcher().match((FieldInstance) memberA, (FieldInstance) memberB);
				gui.onMatchChange(EnumSet.of(MatchType.Field));
			}

			return;
		}

		MethodVarInstance varA = srcPane.getSelectedMethodVar();
		MethodVarInstance varB = dstPane.getSelectedMethodVar();

		if (canMatchVars(varA, varB)) {
			gui.getMatcher().match(varA, varB);
			gui.onMatchChange(EnumSet.of(MatchType.MethodVar));
		}
	}

	private boolean canMatchPerfectMembers(ClassInstance cls) {
		return cls != null && cls.hasMatch() && hasUnmatchedMembers(cls) && hasUnmatchedMembers(cls.getMatch());
	}

	private static boolean hasUnmatchedMembers(ClassInstance cls) {
		for (MethodInstance m : cls.getMethods()) {
			if (!m.hasMatch() && m.isMatchable()) return true;
		}

		for (FieldInstance m : cls.getFields()) {
			if (!m.hasMatch() && m.isMatchable()) return true;
		}

		return false;
	}

	private void matchPerfectMembers() {
		ClassInstance clsA = srcPane.getSelectedClass();

		if (!canMatchPerfectMembers(clsA)) return;

		ClassInstance clsB = clsA.getMatch();

		final double minMethodScore = MethodClassifier.getMaxScore(ClassifierLevel.Full) - 1e-6;
		Map<MethodInstance, MethodInstance> matchedMethods = new IdentityHashMap<>();
		boolean matchedAnyMethods = false;

		for (MethodInstance m : clsA.getMethods()) {
			if (m.hasMatch() || !m.isMatchable()) continue;

			List<RankResult<MethodInstance>> results = MethodClassifier.rank(m, clsB.getMethods(), ClassifierLevel.Full, gui.getEnv());

			if (!results.isEmpty() && results.get(0).getScore() >= minMethodScore && (results.size() == 1 || results.get(1).getScore() < minMethodScore)) {
				MethodInstance match = results.get(0).getSubject();
				MethodInstance prev = matchedMethods.putIfAbsent(match, m);
				if (prev != null) matchedMethods.put(match, null);
			}
		}

		for (Map.Entry<MethodInstance, MethodInstance> entry : matchedMethods.entrySet()) {
			if (entry.getValue() == null) continue;

			gui.getMatcher().match(entry.getValue(), entry.getKey());
			matchedAnyMethods = true;
		}

		final double minFieldScore = FieldClassifier.getMaxScore(ClassifierLevel.Full) - 1e-6;
		Map<FieldInstance, FieldInstance> matchedFields = new IdentityHashMap<>();
		boolean matchedAnyFields = false;

		for (FieldInstance m : clsA.getFields()) {
			if (m.hasMatch() || !m.isMatchable()) continue;

			List<RankResult<FieldInstance>> results = FieldClassifier.rank(m, clsB.getFields(), ClassifierLevel.Full, gui.getEnv());

			if (!results.isEmpty() && results.get(0).getScore() >= minFieldScore && (results.size() == 1 || results.get(1).getScore() < minFieldScore)) {
				FieldInstance match = results.get(0).getSubject();
				FieldInstance prev = matchedFields.putIfAbsent(match, m);
				if (prev != null) matchedFields.put(match, null);
			}
		}

		for (Map.Entry<FieldInstance, FieldInstance> entry : matchedFields.entrySet()) {
			if (entry.getValue() == null) continue;

			gui.getMatcher().match(entry.getValue(), entry.getKey());
			matchedAnyFields = true;
		}

		if (!matchedAnyMethods && !matchedAnyFields) return;

		Set<MatchType> matchedTypes = EnumSet.noneOf(MatchType.class);

		if (matchedAnyMethods) matchedTypes.add(MatchType.Method);
		if (matchedAnyFields) matchedTypes.add(MatchType.Field);

		gui.onMatchChange(matchedTypes);
	}

	private boolean canUnmatchClass(ClassInstance cls) {
		return cls != null && cls.hasMatch();
	}

	private void unmatchClass() {
		ClassInstance cls = srcPane.getSelectedClass();

		if (!canUnmatchClass(cls)) return;

		gui.getMatcher().unmatch(cls);
		gui.onMatchChange(EnumSet.allOf(MatchType.class));
	}

	private boolean canUnmatchMember(MemberInstance<?> member) {
		return member != null && member.getMatch() != null;
	}

	private void unmatchMember() {
		MemberInstance<?> member = srcPane.getSelectedMethod();
		if (member == null) member = srcPane.getSelectedField();

		if (!canUnmatchMember(member)) return;

		gui.getMatcher().unmatch(member);

		if (member instanceof MethodInstance) {
			gui.onMatchChange(EnumSet.of(MatchType.Method, MatchType.MethodVar));
		} else {
			gui.onMatchChange(EnumSet.of(MatchType.Field));
		}
	}

	private boolean canUnmatchVar(MethodVarInstance var) {
		return var != null && var.getMatch() != null;
	}

	private void unmatchVar() {
		MethodVarInstance var = srcPane.getSelectedMethodVar();
		if (!canUnmatchVar(var)) return;

		gui.getMatcher().unmatch(var);
		gui.onMatchChange(EnumSet.of(MatchType.MethodVar));
	}

	private void toggleMatchable() {
		ClassInstance cls = srcPane.getSelectedClass();
		if (cls == null) return;

		if (!cls.isMatchable() || !cls.hasMatch()) {
			boolean newValue = !cls.isMatchable();
			if (!newValue && !cls.hasPotentialMatch()) return;

			cls.setMatchable(newValue);
			gui.onMatchChange(EnumSet.allOf(MatchType.class));
			return;
		}

		MemberInstance<?> member = srcPane.getSelectedMethod();
		if (member == null) member = srcPane.getSelectedField();

		if (!member.isMatchable() || !member.hasMatch()) {
			boolean newValue = !member.isMatchable();
			if (!newValue && !member.hasPotentialMatch()) return;

			if (member.setMatchable(newValue)) {
				gui.onMatchChange(member instanceof MethodInstance ? EnumSet.of(MatchType.Method, MatchType.MethodVar) : EnumSet.of(MatchType.Field));
			}

			return;
		}

		MethodVarInstance var = srcPane.getSelectedMethodVar();

		if (!var.isMatchable() || !var.hasMatch()) {
			boolean newValue = !var.isMatchable();
			if (!newValue && !var.hasPotentialMatch()) return;

			var.setMatchable(!var.isMatchable());
			gui.onMatchChange(EnumSet.of(MatchType.MethodVar));
			return;
		}
	}

	private void addAnonymousClass() {
		ClassInstance clazz = srcPane.getSelectedClass();
		ClassInstance equiv = (clazz == null) ? null : clazz.equiv;

		if (equiv == null) {
			return;
		}

		ClassInstance classNest = dstPane.getSelectedClass();
		MethodInstance methodNest = dstPane.getSelectedMethod();

		if (classNest == null) {
			return;
		}

		gui.getNester().addAnonymousClass(equiv, classNest, methodNest);
		gui.onNestChange();
	}

	private void addInnerClass() {
		ClassInstance clazz = srcPane.getSelectedClass();
		ClassInstance equiv = (clazz == null) ? null : clazz.equiv;

		if (equiv == null) {
			return;
		}

		ClassInstance classNest = dstPane.getSelectedClass();
		MethodInstance methodNest = dstPane.getSelectedMethod();

		if (classNest == null) {
			return;
		}

		gui.getNester().addInnerClass(equiv, classNest, methodNest);
		gui.onNestChange();
	}

	private void toggleNestable() {
		ClassInstance clazz = srcPane.getSelectedClass();
		ClassInstance equiv = (clazz == null) ? null : clazz.equiv;

		if (equiv == null || (equiv.isNestable() && equiv.hasNest())) {
			return;
		}

		equiv.setNestable(!equiv.isNestable());
		gui.onNestChange();
	}

	private void toggleCandidate() {
		dstPane.toggleSelectedNest();
	}

	private void unnest() {
		ClassInstance clazz = srcPane.getSelectedClass();
		ClassInstance equiv = (clazz == null) ? null : clazz.equiv;

		if (equiv == null || !equiv.hasNest()) {
			return;
		}

		gui.getNester().unnest(equiv);
		gui.onNestChange();
	}

	private class SelectListener implements IGuiComponent {
		@Override
		public void onClassSelect(ClassInstance cls) {
			updateButtons();
		}

		@Override
		public void onMethodSelect(MethodInstance method) {
			updateButtons();
		}

		@Override
		public void onFieldSelect(FieldInstance field) {
			updateButtons();
		}

		@Override
		public void onMethodVarSelect(MethodVarInstance arg) {
			updateButtons();
		}

		@Override
		public void onMatchListRefresh() {
			updateMatchButtons();
		}

		@Override
		public void onNestListRefresh() {
			updateNestButtons();
		}
	}

	public Button getMatchButton() {
		return matchButton;
	}

	public Button getMatchableButton() {
		return matchableButton;
	}

	public Button getMatchPerfectMembersButton() {
		return matchPerfectMembersButton;
	}

	public Button getUnmatchClassButton() {
		return unmatchClassButton;
	}

	public Button getUnmatchMemberButton() {
		return unmatchMemberButton;
	}

	public Button getUnmatchVarButton() {
		return unmatchVarButton;
	}

	private final Gui gui;
	private final MatchPaneSrc srcPane;
	private final MatchPaneDst dstPane;

	private final HBox center = new HBox(GuiConstants.padding);
	private final HBox right = new HBox(GuiConstants.padding);

	private final Button matchButton = new Button();
	private final Button matchableButton = new Button();
	private final Button matchPerfectMembersButton = new Button();
	private final Button unmatchClassButton = new Button();
	private final Button unmatchMemberButton = new Button();
	private final Button unmatchVarButton = new Button();

	private final Button addAnonymousClassButton = new Button();
	private final Button addInnerClassButton = new Button();
	private final Button selectedCandidateButton = new Button();
	private final Button nestableButton = new Button();
	private final AccessFlagsMenu accessFlagsMenu;
	private final Button unnestButton = new Button();
}
