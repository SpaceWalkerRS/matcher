package matcher.gui;

import java.util.function.Function;

import org.objectweb.asm.Opcodes;

import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;

import matcher.classifier.nester.Nest;
import matcher.classifier.nester.NestType;
import matcher.type.ClassInstance;

public class AccessFlagsMenu extends MenuBar {
	public AccessFlagsMenu(Gui gui, MatchPaneSrc srcPane, MatchPaneDst dstPane) {
		super();

		this.gui = gui;
		this.srcPane = srcPane;
		this.dstPane = dstPane;

		this.menu = new AccessFlagsMenuImpl();

		getMenus().add(this.menu);
	}

	public void setText(String text) {
		menu.setText(text);
	}

	public void update() {
		menu.update();
	}

	private final Gui gui;
	private final MatchPaneSrc srcPane;
	private final MatchPaneDst dstPane;

	private final AccessFlagsMenuImpl menu;

	private class AccessFlagsMenuImpl extends Menu {
		public AccessFlagsMenuImpl() {
			privateAccess = new CheckMenuItem("private");
			privateAccess.selectedProperty().addListener((value, wasSelected, isSelected) -> {
				if (suppressUpdates || wasSelected == isSelected) return;

				disable(Opcodes.ACC_PROTECTED | Opcodes.ACC_PUBLIC);

				if (isSelected) {
					enable(Opcodes.ACC_PRIVATE);
				} else {
					disable(Opcodes.ACC_PRIVATE);
				}

				update();
			});
			getItems().add(privateAccess);

			protectedAccess = new CheckMenuItem("protected");
			protectedAccess.selectedProperty().addListener((value, wasSelected, isSelected) -> {
				if (suppressUpdates || wasSelected == isSelected) return;

				disable(Opcodes.ACC_PRIVATE | Opcodes.ACC_PUBLIC);

				if (isSelected) {
					enable(Opcodes.ACC_PROTECTED);
				} else {
					disable(Opcodes.ACC_PROTECTED);
				}

				update();
			});
			getItems().add(protectedAccess);

			publicAccess = new CheckMenuItem("public");
			publicAccess.selectedProperty().addListener((value, wasSelected, isSelected) -> {
				if (suppressUpdates || wasSelected == isSelected) return;

				disable(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);

				if (isSelected) {
					enable(Opcodes.ACC_PUBLIC);
				} else {
					disable(Opcodes.ACC_PUBLIC);
				}

				update();
			});
			getItems().add(publicAccess);

			staticAccess = new CheckMenuItem("static");
			staticAccess.selectedProperty().addListener((value, wasSelected, isSelected) -> {
				if (suppressUpdates || wasSelected == isSelected) return;

				if (isSelected) {
					enable(Opcodes.ACC_STATIC);
				} else {
					disable(Opcodes.ACC_STATIC);
				}

				update();
			});
			getItems().add(staticAccess);
		}

		private void enable(int opcodes) {
			setAccess(access -> access | opcodes);
		}

		private void disable(int opcodes) {
			setAccess(access -> access & ~opcodes);
		}

		private void setAccess(Function<Integer, Integer> f) {
			ClassInstance clazz = srcPane.getSelectedClass();
			ClassInstance equiv = (clazz == null) ? null : clazz.equiv;

			if (equiv == null) {
				return;
			}

			Nest nest = equiv.getNest();

			if (nest == null || nest.getType() != NestType.INNER) {
				return;
			}

			Integer access = equiv.getInnerAccess();

			if (access == null) {
				access = equiv.getAccess();
			}

			access = f.apply(access);

			if (access != null) {
				equiv.setInnerAccess(access);
			}
		}

		public void update() {
			boolean disable = AccessFlagsMenu.this.isDisable();
			setDisable(disable);

			if (disable) {
				return;
			}

			ClassInstance clazz = srcPane.getSelectedClass();
			ClassInstance equiv = (clazz == null) ? null : clazz.equiv;

			if (equiv == null) {
				return;
			}

			Nest nest = equiv.getNest();

			if (nest == null || nest.getType() != NestType.INNER) {
				return;
			}

			suppressUpdates = true;

			Integer access = equiv.getInnerAccess();

			if (access == null) {
				access = equiv.getAccess();
			}

			privateAccess.setSelected((access & Opcodes.ACC_PRIVATE) != 0);
			protectedAccess.setSelected((access & Opcodes.ACC_PROTECTED) != 0);
			publicAccess.setSelected((access & Opcodes.ACC_PUBLIC) != 0);

			boolean allowStatic = equiv.canBeStatic();

			staticAccess.setDisable(!allowStatic);
			staticAccess.setSelected(allowStatic && (access & Opcodes.ACC_STATIC) != 0);

			suppressUpdates = false;
		}

		private final CheckMenuItem privateAccess;
		private final CheckMenuItem protectedAccess;
		private final CheckMenuItem publicAccess;
		private final CheckMenuItem staticAccess;

		private boolean suppressUpdates;
	}
}
