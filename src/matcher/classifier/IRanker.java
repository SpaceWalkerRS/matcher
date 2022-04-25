package matcher.classifier;

import java.util.List;

import matcher.type.ClassEnvironment;
import matcher.type.Matchable;

public interface IRanker<T extends Matchable<T>> {
	List<RankResult<T>> rank(T src, T[] dsts, ClassifierLevel level, ClassEnvironment env, double maxMismatch);
}