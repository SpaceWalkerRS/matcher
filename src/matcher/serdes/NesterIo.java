package matcher.serdes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import matcher.Nester;
import matcher.Util;
import matcher.classifier.nester.Nest;
import matcher.type.ClassEnvironment;
import matcher.type.ClassInstance;
import matcher.type.MethodInstance;

public class NesterIo {
	public static void read(Path path, Nester nester) {
		try (BufferedReader br = Files.newBufferedReader(path)) {
			String line;

			while ((line = br.readLine()) != null) {
				String[] args = line.split("\\s");

				if (args.length != 6) {
					System.err.println("Incorrect number of arguments for mapping \'" + line + "\' - expected 6, got " + args.length + "...");
					continue;
				}

				String className = args[0];
				String enclClassName = args[1];
				String enclMethodName = args[2];
				String enclMethodDesc = args[3];
				String innerName = args[4];
				String accessString = args[5];

				if (className == null || className.isEmpty()) {
					System.err.println("Invalid mapping \'" + line + "\': missing class name argument!");
					continue;
				}
				if (enclClassName == null || enclClassName.isEmpty()) {
					System.err.println("Invalid mapping \'" + line + "\': missing enclosing class name argument!");
					continue;
				}
				if (innerName == null || innerName.isEmpty()) {
					System.err.println("Invalid mapping \'" + line + "\': missing inner class name argument!");
					continue;
				}

				boolean emptyName = (enclMethodName == null) || enclMethodName.isEmpty();
				boolean emptyDesc = (enclMethodDesc == null) || enclMethodDesc.isEmpty();

				if (emptyName || emptyDesc) {
					enclMethodName = null;
					enclMethodDesc = null;
				}

				int anonIndex = -1;

				try {
					anonIndex = Integer.parseInt(innerName);

					if (anonIndex < 1) {
						System.err.println("Invalid mapping \'" + line + "\': invalid anonymous class index!");
						continue;
					}
				} catch (NumberFormatException e) {

				}

				Integer access = null;

				try {
					access = Integer.parseInt(accessString);
				} catch (NumberFormatException e) {

				}

				if (access == null || access < 0) {
					System.err.println("Invalid mapping \'" + line + "\': invalid access flags!");
					continue;
				}

				NestP nest = new NestP(className, enclClassName, enclMethodName, enclMethodDesc, innerName, access);
				nest = fixNest(nester, nest);

				if (nest == null) {
					continue;
				}

				nestClass(nester, nest.className, nest.enclClassName, nest.enclMethodName, nest.enclMethodDesc, nest.innerName, nest.access, anonIndex);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static class NestP {

		public String className;
		public String enclClassName;
		public String enclMethodName;
		public String enclMethodDesc;
		public String innerName;
		public int access;

		NestP(String className, String enclClassName, String enclMethodName, String enclMethodDesc, String innerName, int access) {
			this.className = className;
			this.enclClassName = enclClassName;
			this.enclMethodName = enclMethodName;
			this.enclMethodDesc = enclMethodDesc;
			this.innerName = innerName;
			this.access = access;
		}
	}

	private static NestP fixNest(Nester nester, NestP nest) {
		ClassEnvironment env = nester.getEnv();
		ClassInstance clazz = env.getClsByNameB(nest.className);

		if (clazz == null) {
			System.err.println("WARNING: class " + nest.className + " does not exist!");
			return null;
		}

		if (nest.enclMethodName == null) {

		} else {
			MethodInstance[] constructors = clazz.getInstanceConstructors();

			if (constructors.length != 1) {
				System.err.println("WARNING: supposedly anonymous class " + nest.className + " has " + constructors.length + " constructors!");
				return null;
			}

			MethodInstance constr = constructors[0];
			Set<MethodInstance> references = constr.getRefsIn();

			if (references.size() != 1) {
				System.err.println("WARNING: supposedly anonymous class " + nest.className + " is created " + references.size() + " times!");
				return null;
			}

			MethodInstance enclMethod = references.iterator().next();
			ClassInstance enclClass = enclMethod.getCls();

			if (enclClass == clazz) {
				System.err.println("WARNING: supposedly anonymous class " + nest.className + " is created by itself!");
				return null;
			}

			String o = nest.enclClassName + "." + nest.enclMethodName + nest.enclMethodDesc;

			nest.enclClassName = enclClass.getName();
			nest.enclMethodName = enclMethod.getName();
			nest.enclMethodDesc = enclMethod.getDesc();

			String n = nest.enclClassName + "." + nest.enclMethodName + nest.enclMethodDesc;

			if (!n.equals(o)) {
				System.err.println("moving anonymous class " + nest.className + " from " + o + " to " + n);
			}
		}

		return nest;
	}

	private static void nestClass(Nester nester, String className, String enclClassName, String enclMethodName, String enclMethodDesc, String innerName, int access, int anonIndex) {
		ClassEnvironment env = nester.getEnv();
		ClassInstance clazz = env.getClsByNameB(className);

		if (clazz == null) {
			System.err.println("ignoring mapping for class \'" + className + "\': class does not exist!");
			return;
		}

		ClassInstance enclClass = env.getClsByNameB(enclClassName);

		if (enclClass == null) {
			System.err.println("ignoring mapping for class \'" + className + "\': enclosing class \'" + enclClassName + "\' does not exist!");
			return;
		}

		MethodInstance enclMethod = null;

		if (enclMethodName != null) {
			enclMethod = enclClass.getMethod(enclMethodName, enclMethodDesc);

			if (enclMethod == null) {
				System.err.println("ignoring mapping for class \'" + className + "\': enclosing method \'" + enclMethodName + "\' does not exist!");
				return;
			}
		}

		if (anonIndex > 0) {
			nester.addAnonymousClass(clazz, enclClass, enclMethod);
		} else {
			nester.addInnerClass(clazz, enclClass, enclMethod);
			clazz.setInnerName(stripLocalClassPrefix(innerName));
		}

		clazz.setInnerAccess(access);
	}

	private static String stripLocalClassPrefix(String innerName) {
		int nameStart = 0;

		// local class names start with a number prefix
		while (nameStart < innerName.length() && Character.isDigit(innerName.charAt(nameStart))) {
			nameStart++;
		}
		// if entire inner name is a number, this class is anonymous, not local
		if (nameStart == innerName.length()) {
			nameStart = 0;
		}

		return innerName.substring(nameStart);
	}

	public static boolean write(Nester nester, Path path) throws IOException {
		List<ClassInstance> classes = new ArrayList<>();

		for (ClassInstance clazz : nester.getEnv().getClassesA()) {
			if (!clazz.isReal()) {
				continue;
			}

			ClassInstance equiv = clazz.equiv;

			if (equiv.hasNest()) {
				classes.add(equiv);
			}
		}

		if (classes.isEmpty()) {
			return false;
		}

		classes.sort(Comparator.comparing(ClassInstance::getName, Util::compareNatural));

		try (Writer w = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			for (ClassInstance clazz : classes) {
				Nest nest = clazz.getNest();

				ClassInstance enclClass = nest.getEnclosingClass();
				MethodInstance enclMethod = nest.getEnclosingMethod();

				nestClass(w, clazz, enclClass, enclMethod);
			}
		}

		return true;
	}

	private static void nestClass(Writer w, ClassInstance clazz, ClassInstance enclClass, MethodInstance enclMethod) throws IOException {
		Integer rawAccess = clazz.getInnerAccess();

		if (rawAccess == null) {
			rawAccess = clazz.getAccess();
		}

		String className = clazz.getName();
		String enclClassName = enclClass.getName();
		String enclMethodName = "";
		String enclMethodDesc = "";
		String innerName = clazz.getLocalPrefix() + clazz.getInnerName();
		String access = String.valueOf(rawAccess);

		if (enclMethod != null) {
			enclMethodName = enclMethod.getName();
			enclMethodDesc = enclMethod.getDesc();
		}

		w.write(className);
		w.write("\t");
		w.write(enclClassName);
		w.write("\t");
		w.write(enclMethodName);
		w.write("\t");
		w.write(enclMethodDesc);
		w.write("\t");
		w.write(innerName);
		w.write("\t");
		w.write(access);
		w.write("\n");
	}
}
