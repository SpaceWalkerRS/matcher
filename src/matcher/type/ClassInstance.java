package matcher.type;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import matcher.NameType;
import matcher.SimilarityChecker;
import matcher.Util;
import matcher.bcremap.AsmClassRemapper;
import matcher.bcremap.AsmRemapper;
import matcher.classifier.ClassifierUtil;
import matcher.classifier.nester.Nest;
import matcher.classifier.nester.NestRankResult;
import matcher.classifier.nester.NestType;
import matcher.classifier.nester.NestedClassClassifier;
import matcher.type.Signature.ClassSignature;

public final class ClassInstance implements Matchable<ClassInstance> {
	/**
	 * Create a shared unknown class.
	 */
	ClassInstance(String id, ClassEnv env) {
		this(id, null, env, null, false, false, null);

		assert id.indexOf('[') == -1 : id;
	}

	/**
	 * Create a known class (class path).
	 */
	public ClassInstance(String id, URI origin, ClassEnv env, ClassNode asmNode) {
		this(id, origin, env, asmNode, false, false, null);

		assert id.indexOf('[') == -1 : id;
	}

	/**
	 * Create an array class.
	 */
	ClassInstance(String id, ClassInstance elementClass) {
		this(id, null, elementClass.env, null, elementClass.nameObfuscated, false, elementClass);

		assert id.startsWith("[") : id;
		assert id.indexOf('[', getArrayDimensions()) == -1 : id;
		assert !elementClass.isArray();

		elementClass.addArray(this);
	}

	/**
	 * Create a non-array class.
	 */
	ClassInstance(String id, URI origin, ClassEnv env, ClassNode asmNode, boolean nameObfuscated) {
		this(id, origin, env, asmNode, nameObfuscated, true, null);

		assert id.startsWith("L") : id;
		assert id.indexOf('[') == -1 : id;
		assert asmNode != null;
	}

	private ClassInstance(String id, URI origin, ClassEnv env, ClassNode asmNode, boolean nameObfuscated, boolean input, ClassInstance elementClass) {
		if (id.isEmpty()) throw new IllegalArgumentException("empty id");
		if (env == null) throw new NullPointerException("null env");

		this.id = id;
		this.origin = origin;
		this.env = env;
		this.asmNodes = asmNode == null ? null : new ClassNode[] { asmNode };
		this.nameObfuscated = nameObfuscated;
		this.input = input;
		this.elementClass = elementClass;

		if (env.isShared()) matchedClass = this;

		if (isArray()) {
			this.elementClass.markNotAnonymous();
		}
	}

	@Override
	public MatchableKind getKind() {
		return MatchableKind.CLASS;
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public String getName() {
		return getName(id);
	}

	@Override
	public String getName(NameType type) {
		return getName(type, true);
	}

	public String getName(NameType type, boolean includeOuter) {
		if (type == NameType.PLAIN) {
			return includeOuter ? getName() : getInnerName0(getName());
		} else if (elementClass != null) {
			boolean isPrimitive = elementClass.isPrimitive();
			StringBuilder ret = new StringBuilder();

			ret.append(id, 0, getArrayDimensions());

			if (!isPrimitive) ret.append('L');
			ret.append(elementClass.getName(type, includeOuter));
			if (!isPrimitive) ret.append(';');

			return ret.toString();
		} else if (type == NameType.UID_PLAIN) {
			int uid = getUid();
			if (uid >= 0) return env.getGlobal().classUidPrefix+uid;
		}

		boolean locTmp = type == NameType.MAPPED_LOCTMP_PLAIN || type == NameType.LOCTMP_PLAIN;
		String ret;
		boolean fromMatched; // name retrieved from matched class

		if (type.mapped && mappedName != null) {
			// MAPPED_*, local name available
			ret = mappedName;
			fromMatched = false;
		} else if (type.mapped && matchedClass != null && canTransferMatchedName(matchedClass.mappedName)) {
			// MAPPED_*, remote name available
			ret = matchedClass.mappedName;
			fromMatched = true;
		} else if (type.mapped && !nameObfuscated) {
			// MAPPED_*, local deobf
			ret = getInnerName0(getName());
			fromMatched = false;
		} else if (type.mapped && matchedClass != null && !matchedClass.nameObfuscated) {
			// MAPPED_*, remote deobf
			ret = matchedClass.getInnerName0(matchedClass.getName());
			fromMatched = true;
		} else if (type.isAux() && auxName != null && auxName.length > type.getAuxIndex() && auxName[type.getAuxIndex()] != null) {
			ret = auxName[type.getAuxIndex()];
			fromMatched = false;
		} else if (type.isAux() && matchedClass != null && matchedClass.auxName != null && matchedClass.auxName.length > type.getAuxIndex() && canTransferMatchedName(matchedClass.auxName[type.getAuxIndex()])) {
			ret = matchedClass.auxName[type.getAuxIndex()];
			fromMatched = true;
		} else if (type.tmp && matchedClass != null && matchedClass.tmpName != null) {
			// MAPPED_TMP_* with obf name or TMP_*, remote name available
			ret = matchedClass.tmpName;
			fromMatched = true;
		} else if ((type.tmp || locTmp) && tmpName != null) {
			// MAPPED_TMP_* or MAPPED_LOCTMP_* with obf name or TMP_* or LOCTMP_*, local name available
			ret = tmpName;
			fromMatched = false;
		} else if (type.plain) {
			ret = getInnerName0(getName());
			fromMatched = false;
		} else {
			return null;
		}

		assert ret == null || !hasOuterName(ret) : ret;

		if (!includeOuter) return ret;

		/*
		 * ret-outer: whether ret's source has an outer class
		 * this-outer: whether this has an outer class
		 * has outer class -> assume not normal name with pkg, but plain inner class name
		 *
		 * ret-outer this-outer action                    ret-example this-example result-example
		 *     n         n      ret                        a/b         d/e          a/b
		 *     n         y      this.outer+ret.strip-pkg   a/b         d/e$f        d/e$b
		 *     y         n      ret.outer.pkg+ret          a/b$c       d/e          a/c
		 *     y         y      this.outer+ret             a/b$c       d/e$f        d/e$c
		 */

		if (!fromMatched || (outerClass == null) == (matchedClass.outerClass == null)) { // ret-outer == this-outer
			return outerClass != null ? getNestedName(outerClass.getName(type), ret) : ret;
		} else if (outerClass != null) { // ret is normal name, strip package from ret before concatenating
			return getNestedName(outerClass.getName(type), ret.substring(ret.lastIndexOf('/') + 1));
		} else { // ret is an outer name, restore pkg
			String matchedOuterName = matchedClass.outerClass.getName(type);
			int pkgEnd = matchedOuterName.lastIndexOf('/');
			if (pkgEnd > 0) ret = matchedOuterName.substring(0, pkgEnd + 1).concat(ret);

			return ret;
		}
	}

	private boolean canTransferMatchedName(String name) {
		if (name == null || name.isEmpty()) return false;

		return !matchedClass.nameObfuscated
				|| outerClass != null || matchedClass.outerClass == null // no outer -> inner transfer
				|| Character.isJavaIdentifierStart(name.charAt(0));
	}

	private String getInnerName0(String name) {
		if (outerClass == null && (isReal() || !hasOuterName(name))) {
			return name;
		} else {
			return getInnerName(name);
		}
	}

	@Override
	public String getDisplayName(NameType type, boolean full) {
		char lastChar = id.charAt(id.length() - 1);
		String ret;

		if (lastChar != ';') { // primitive or primitive array
			switch (lastChar) {
			case 'B': ret = "byte"; break;
			case 'C': ret = "char"; break;
			case 'D': ret = "double"; break;
			case 'F': ret = "float"; break;
			case 'I': ret = "int"; break;
			case 'J': ret = "long"; break;
			case 'S': ret = "short"; break;
			case 'V': ret = "void"; break;
			case 'Z': ret = "boolean"; break;
			default: throw new IllegalStateException("invalid class desc: "+id);
			}
		} else {
			ret = getName(type).replace('/', '.');
		}

		int dims = getArrayDimensions();

		if (dims > 0) {
			StringBuilder sb;

			if (lastChar != ';') { // primitive array, ret is in plain name form from above
				assert !ret.startsWith("[") && !ret.endsWith(";");

				sb = new StringBuilder(ret.length() + 2 * dims);
				sb.append(ret);
			} else { // reference array, in dot separated id form
				assert ret.startsWith("[") && ret.endsWith(";");

				sb = new StringBuilder(ret.length() + dims - 2);
				sb.append(ret, dims + 1, ret.length() - 1);
			}

			for (int i = 0; i < dims; i++) {
				sb.append("[]");
			}

			ret = sb.toString();
		}

		return full ? ret : ret.substring(ret.lastIndexOf('.') + 1);
	}

	public boolean isReal() {
		return origin != null;
	}

	public URI getOrigin() {
		return origin;
	}

	@Override
	public Matchable<?> getOwner() {
		return null;
	}

	@Override
	public ClassEnv getEnv() {
		return env;
	}

	public ClassNode[] getAsmNodes() {
		return asmNodes;
	}

	public URI getAsmNodeOrigin(int index) {
		if (index < 0 || index > 0 && (asmNodeOrigins == null || index >= asmNodeOrigins.length)) throw new IndexOutOfBoundsException(index);

		return index == 0 ? origin : asmNodeOrigins[index];
	}

	public ClassNode getMergedAsmNode() {
		if (asmNodes == null) return null;
		if (asmNodes.length == 1) return asmNodes[0];

		return asmNodes[0]; // TODO: actually merge
	}

	void addAsmNode(ClassNode node, URI origin) {
		if (!input) throw new IllegalStateException("not mergeable");

		asmNodes = Arrays.copyOf(asmNodes, asmNodes.length + 1);
		asmNodes[asmNodes.length - 1] = node;

		if (asmNodeOrigins == null) {
			asmNodeOrigins = new URI[2];
			asmNodeOrigins[0] = this.origin;
		} else {
			asmNodeOrigins = Arrays.copyOf(asmNodeOrigins, asmNodeOrigins.length + 1);
		}

		asmNodeOrigins[asmNodeOrigins.length - 1] = origin;
	}

	@Override
	public boolean hasPotentialMatch() {
		if (matchedClass != null) return true;
		if (!isMatchable()) return false;

		for (ClassInstance o : env.getOther().getClasses()) {
			if (o.isReal() && ClassifierUtil.checkPotentialEquality(this, o)) return true;
		}

		return false;
	}

	@Override
	public boolean isMatchable() {
		return matchable;
	}

	@Override
	public boolean setMatchable(boolean matchable) {
		if (!matchable && matchedClass != null) return false;

		this.matchable = matchable;

		return true;
	}

	@Override
	public ClassInstance getMatch() {
		return matchedClass;
	}

	public void setMatch(ClassInstance cls) {
		assert cls == null || isMatchable();
		assert cls == null || cls.getEnv() != env && !cls.getEnv().isShared();

		this.matchedClass = cls;
	}

	@Override
	public boolean isFullyMatched(boolean recursive) {
		if (matchedClass == null) return false;

		for (MethodInstance m : methods) {
			if (m.hasPotentialMatch() && (!m.hasMatch() || recursive && !m.isFullyMatched(true))) {
				return false;
			}
		}

		for (FieldInstance m : fields) {
			if (m.hasPotentialMatch() && (!m.hasMatch() || recursive && !m.isFullyMatched(true))) {
				return false;
			}
		}

		return true;
	}

	@Override
	public float getSimilarity() {
		if (matchedClass == null) return 0;

		return SimilarityChecker.compare(this, matchedClass);
	}

	@Override
	public boolean isNameObfuscated() {
		return nameObfuscated;
	}

	public boolean isInput() {
		return input;
	}

	public ClassInstance getElementClass() {
		if (!isArray()) throw new IllegalStateException("not applicable to non-array");

		return elementClass;
	}

	public ClassInstance getElementClassShallow(boolean create) {
		if (!isArray()) throw new IllegalStateException("not applicable to non-array");

		int dims = getArrayDimensions();
		if (dims <= 1) return elementClass;

		String retId = id.substring(1);

		return create ? env.getCreateClassInstance(retId) : env.getClsById(retId);
	}

	public ClassSignature getSignature() {
		return signature;
	}

	void setSignature(ClassSignature signature) {
		this.signature = signature;
	}

	public boolean isPrimitive() {
		char start = id.charAt(0);

		return start != 'L' && start != '[';
	}

	public int getSlotSize() {
		char start = id.charAt(0);

		return (start == 'D' || start == 'J') ? 2 : 1;
	}

	public boolean isArray() {
		return elementClass != null;
	}

	public int getArrayDimensions() {
		if (elementClass == null) return 0;

		for (int i = 0; i < id.length(); i++) {
			if (id.charAt(i) != '[') return i;
		}

		throw new IllegalStateException("invalid id: "+id);
	}

	public ClassInstance[] getArrays() {
		return arrays;
	}

	private void addArray(ClassInstance cls) {
		assert !Arrays.asList(arrays).contains(cls);

		arrays = Arrays.copyOf(arrays, arrays.length + 1);
		arrays[arrays.length - 1] = cls;
	}

	public int getAccess() {
		int ret;

		if (asmNodes != null) {
			ret = asmNodes[0].access;

			if (superClass != null && superClass.id.equals("Ljava/lang/Record;")) { // ACC_RECORD is added by ASM through Record component attribute presence, don't trust the flag to handle stripping of the attribute
				ret |= Opcodes.ACC_RECORD;
			}
		} else {
			ret = Opcodes.ACC_PUBLIC;

			if (!implementers.isEmpty()) {
				ret |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
			} else if (superClass != null && superClass.id.equals("Ljava/lang/Enum;")) {
				ret |= Opcodes.ACC_ENUM;
				if (childClasses.isEmpty()) ret |= Opcodes.ACC_FINAL;
			} else if (superClass != null && superClass.id.equals("Ljava/lang/Record;")) {
				ret |= Opcodes.ACC_RECORD;
				if (childClasses.isEmpty()) ret |= Opcodes.ACC_FINAL;
			} else if (interfaces.size() == 1 && interfaces.iterator().next().id.equals("Ljava/lang/annotation/Annotation;")) {
				ret |= Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
			}
		}

		return ret;
	}

	public boolean isPublic() {
		return (getAccess() & Opcodes.ACC_PUBLIC) != 0;
	}

	public boolean isProtected() {
		return (getAccess() & Opcodes.ACC_PROTECTED) != 0;
	}

	public boolean isPrivate() {
		return (getAccess() & Opcodes.ACC_PRIVATE) != 0;
	}

	public boolean isPackagePrivate() {
		return !isPublic() && !isProtected() && !isPrivate();
	}

	public boolean isStatic() {
		return (getAccess() & Opcodes.ACC_STATIC) != 0;
	}

	public boolean isInterface() {
		return (getAccess() & Opcodes.ACC_INTERFACE) != 0;
	}

	public boolean isEnum() {
		return (getAccess() & Opcodes.ACC_ENUM) != 0;
	}

	public boolean isAnnotation() {
		return (getAccess() & Opcodes.ACC_ANNOTATION) != 0;
	}

	public boolean isRecord() {
		return (getAccess() & Opcodes.ACC_RECORD) != 0 || superClass != null && superClass.id.equals("Ljava/lang/Record;");
	}

	public boolean isSynthetic() {
		return (getAccess() & Opcodes.ACC_SYNTHETIC) != 0;
	}

	public MethodInstance getMethod(String id) {
		return methodIdx.get(id);
	}

	public FieldInstance getField(String id) {
		return fieldIdx.get(id);
	}

	public MethodInstance getMethod(String name, String desc) {
		if (desc != null) {
			return methodIdx.get(MethodInstance.getId(name, desc));
		} else {
			MethodInstance ret = null;

			for (MethodInstance method : methods) {
				if (method.origName.equals(name)) {
					if (ret != null) return null; // non-unique

					ret = method;
				}
			}

			return ret;
		}
	}

	public MethodInstance getMethod(String name, String desc, NameType nameType) {
		if (nameType == NameType.PLAIN) return getMethod(name, desc);

		MethodInstance ret = null;

		methodLoop: for (MethodInstance method : methods) {
			String mappedName = method.getName(nameType);

			if (mappedName == null || !name.equals(mappedName)) {
				continue;
			}

			if (desc != null) {
				assert desc.startsWith("(");
				int idx = 0;
				int pos = 1;
				boolean last = false;

				do {
					char c = desc.charAt(pos);
					ClassInstance match;

					if (c == ')') {
						if (idx != method.args.length) continue methodLoop;
						last = true;
						pos++;
						c = desc.charAt(pos);
						match = method.retType;
					} else {
						if (idx >= method.args.length) continue methodLoop;
						match = method.args[idx].type;
					}

					if (c == '[') { // array cls
						int dims = 1;
						while ((c = desc.charAt(++pos)) == '[') dims++;

						if (match.getArrayDimensions() != dims) continue methodLoop;
						match = match.elementClass;
					} else {
						if (match.isArray()) continue methodLoop;
					}

					int end;

					if (c != 'L') { // primitive cls
						end = pos + 1;
					} else {
						end = desc.indexOf(';', pos + 1) + 1;
						assert end != 0;
					}

					String clsMappedName = match.getName(nameType);
					if (clsMappedName == null) continue methodLoop;

					if (c != 'L') {
						if (clsMappedName.length() != end - pos || !desc.startsWith(clsMappedName, pos)) continue methodLoop;
					} else {
						if (clsMappedName.length() != end - pos - 2 || !desc.startsWith(clsMappedName, pos + 1)) continue methodLoop;
					}

					pos = end;
					idx++;
				} while (!last);
			}

			if (ret != null) return null; // non-unique

			ret = method;
		}

		return ret;
	}

	public FieldInstance getField(String name, String desc) {
		if (desc != null) {
			return fieldIdx.get(FieldInstance.getId(name, desc));
		} else {
			FieldInstance ret = null;

			for (FieldInstance field : fields) {
				if (field.origName.equals(name)) {
					if (ret != null) return null; // non-unique

					ret = field;
				}
			}

			return ret;
		}
	}

	public FieldInstance getField(String name, String desc, NameType nameType) {
		if (nameType == NameType.PLAIN) return getField(name, desc);

		FieldInstance ret = null;

		for (FieldInstance field : fields) {
			String mappedName = field.getName(nameType);

			if (mappedName == null || !name.equals(mappedName)) {
				continue;
			}

			if (desc != null) {
				String clsMappedName = field.type.getName(nameType);
				if (clsMappedName == null) continue;

				if (desc.startsWith("[") || !desc.endsWith(";")) {
					if (!desc.equals(clsMappedName)) continue;
				} else {
					if (desc.length() != clsMappedName.length() + 2 || !desc.startsWith(clsMappedName, 1)) continue;
				}
			}

			if (ret != null) return null; // non-unique

			ret = field;
		}

		return ret;
	}

	public MethodInstance resolveMethod(String name, String desc, boolean toInterface) {
		// toInterface = false: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3
		// toInterface = true: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.4
		// TODO: access check after resolution

		assert asmNodes == null || isInterface() == toInterface;

		if (!toInterface) {
			MethodInstance ret = resolveSignaturePolymorphicMethod(name);
			if (ret != null) return ret;

			ret = getMethod(name, desc);
			if (ret != null) return ret; // <this> is unconditional

			ClassInstance cls = this;

			while ((cls = cls.superClass) != null) {
				ret = cls.resolveSignaturePolymorphicMethod(name);
				if (ret != null) return ret;

				ret = cls.getMethod(name, desc);
				if (ret != null) return ret;
			}

			return resolveInterfaceMethod(name, desc);
		} else {
			MethodInstance ret = getMethod(name, desc);
			if (ret != null) return ret; // <this> is unconditional

			if (superClass != null) {
				assert superClass.id.equals("Ljava/lang/Object;");

				ret = superClass.getMethod(name, desc);
				if (ret != null && (!ret.isReal() || (ret.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == Opcodes.ACC_PUBLIC)) return ret;
			}

			return resolveInterfaceMethod(name, desc);
		}
	}

	private MethodInstance resolveSignaturePolymorphicMethod(String name) {
		if (id.equals("Ljava/lang/invoke/MethodHandle;")) { // check for signature polymorphic method - jvms-2.9
			MethodInstance ret = getMethod(name, "([Ljava/lang/Object;)Ljava/lang/Object;");
			final int reqFlags = Opcodes.ACC_VARARGS | Opcodes.ACC_NATIVE;

			if (ret != null && (!ret.isReal() || (ret.access & reqFlags) == reqFlags)) {
				return ret;
			}
		}

		return null;
	}

	private MethodInstance resolveInterfaceMethod(String name, String desc) {
		Queue<ClassInstance> queue = new ArrayDeque<>();
		Set<ClassInstance> queued = Util.newIdentityHashSet();
		ClassInstance cls = this;

		do {
			for (ClassInstance ifCls : cls.interfaces) {
				if (queued.add(ifCls)) queue.add(ifCls);
			}
		} while ((cls = cls.superClass) != null);

		if (queue.isEmpty()) return null;

		Set<MethodInstance> matches = Util.newIdentityHashSet();
		boolean foundNonAbstract = false;

		while ((cls = queue.poll()) != null) {
			MethodInstance ret = cls.getMethod(name, desc);

			if (ret != null
					&& (!ret.isReal() || (ret.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0)) {
				matches.add(ret);

				if (ret.isReal() && (ret.access & Opcodes.ACC_ABSTRACT) == 0) { // jvms prefers the closest non-abstract method
					foundNonAbstract = true;
				}
			}

			for (ClassInstance ifCls : cls.interfaces) {
				if (queued.add(ifCls)) queue.add(ifCls);
			}
		}

		if (matches.isEmpty()) return null;
		if (matches.size() == 1) return matches.iterator().next();

		// non-abstract methods take precedence over non-abstract methods, remove all abstract ones if there's at least 1 non-abstract

		if (foundNonAbstract) {
			for (Iterator<MethodInstance> it = matches.iterator(); it.hasNext(); ) {
				MethodInstance m = it.next();

				if (!m.isReal() || (m.access & Opcodes.ACC_ABSTRACT) != 0) {
					it.remove();
				}
			}

			assert !matches.isEmpty();
			if (matches.size() == 1) return matches.iterator().next();
		}

		// eliminate not maximally specific method declarations, i.e. those that have a child method in matches

		for (Iterator<MethodInstance> it = matches.iterator(); it.hasNext(); ) {
			MethodInstance m = it.next();

			cmpLoop: for (MethodInstance m2 : matches) {
				if (m2 == m) continue;

				if (m2.cls.interfaces.contains(m.cls)) { // m2 is a direct child of m, so m isn't maximally specific
					it.remove();
					break;
				}

				queue.addAll(m2.cls.interfaces);

				while ((cls = queue.poll()) != null) {
					if (cls.interfaces.contains(m.cls)) { // m2 is an indirect child of m, so m isn't maximally specific
						it.remove();
						queue.clear();
						break cmpLoop;
					}

					queue.addAll(cls.interfaces);
				}
			}
		}

		// return an arbitrary choice

		return matches.iterator().next();
	}

	public FieldInstance resolveField(String name, String desc) {
		FieldInstance ret = getField(name, desc);
		if (ret != null) return ret;

		if (!interfaces.isEmpty()) {
			Deque<ClassInstance> queue = new ArrayDeque<>();
			queue.addAll(interfaces);
			ClassInstance cls;

			while ((cls = queue.pollFirst()) != null) {
				ret = cls.getField(name, desc);
				if (ret != null) return ret;

				for (ClassInstance iface : cls.interfaces) {
					queue.addFirst(iface);
				}
			}
		}

		ClassInstance cls = superClass;

		while (cls != null) {
			ret = cls.getField(name, desc);
			if (ret != null) return ret;

			cls = cls.superClass;
		}

		return null;
	}

	public MethodInstance getMethod(int pos) {
		if (pos < 0 || pos >= methods.length) throw new IndexOutOfBoundsException();
		if (asmNodes == null) throw new UnsupportedOperationException();

		return methods[pos];
	}

	public FieldInstance getField(int pos) {
		if (pos < 0 || pos >= fields.length) throw new IndexOutOfBoundsException();
		if (asmNodes == null) throw new UnsupportedOperationException();

		return fields[pos];
	}

	public MethodInstance[] getMethods() {
		return methods;
	}

	public FieldInstance[] getFields() {
		return fields;
	}

	public ClassInstance getOuterClass() {
		return outerClass;
	}

	public Set<ClassInstance> getInnerClasses() {
		return innerClasses;
	}

	public ClassInstance getSuperClass() {
		return superClass;
	}

	public Set<ClassInstance> getChildClasses() {
		return childClasses;
	}

	public Set<ClassInstance> getInterfaces() {
		return interfaces;
	}

	public Set<ClassInstance> getImplementers() {
		return implementers;
	}

	public Set<MethodInstance> getMethodTypeRefs() {
		return methodTypeRefs;
	}

	public Set<FieldInstance> getFieldTypeRefs() {
		return fieldTypeRefs;
	}

	public Set<MethodVarInstance> getArgTypeRefs() {
		return argTypeRefs;
	}

	public Set<String> getStrings() {
		return strings;
	}

	public boolean isShared() {
		return matchedClass == this;
	}

	@Override
	public boolean hasLocalTmpName() {
		return tmpName != null;
	}

	public void setTmpName(String tmpName) {
		this.tmpName = tmpName;
	}

	@Override
	public int getUid() {
		if (uid >= 0) {
			if (matchedClass != null && matchedClass.uid >= 0) {
				return Math.min(uid, matchedClass.uid);
			} else {
				return uid;
			}
		} else if (matchedClass != null) {
			return matchedClass.uid;
		} else {
			return -1;
		}
	}

	public void setUid(int uid) {
		this.uid = uid;
	}

	@Override
	public boolean hasMappedName() {
		return mappedName != null
				|| matchedClass != null && matchedClass.mappedName != null
				|| elementClass != null && elementClass.hasMappedName()
				/*|| outerClass != null && outerClass.hasMappedName() TODO: for anonymous only?*/;
	}

	public boolean hasNoFullyMappedName() {
		assert elementClass == null || outerClass == null; // array classes can't have an outer class

		return outerClass != null
				&& mappedName == null
				&& (matchedClass == null || matchedClass.mappedName == null);
	}

	public void setMappedName(String mappedName) {
		assert mappedName == null || !hasOuterName(mappedName);

		this.mappedName = mappedName;
	}

	@Override
	public String getMappedComment() {
		if (mappedComment != null) {
			return mappedComment;
		} else if (matchedClass != null) {
			return matchedClass.mappedComment;
		} else {
			return null;
		}
	}

	@Override
	public void setMappedComment(String comment) {
		if (comment != null && comment.isEmpty()) comment = null;

		this.mappedComment = comment;
	}

	@Override
	public boolean hasAuxName(int index) {
		return auxName != null && auxName.length > index && auxName[index] != null;
	}

	public void setAuxName(int index, String name) {
		assert name == null || !hasOuterName(name);

		if (this.auxName == null) this.auxName = new String[NameType.AUX_COUNT];
		this.auxName[index] = name;
	}

	public boolean isAssignableFrom(ClassInstance c) {
		if (c == this) return true;
		if (isPrimitive()) return false;

		if (!isInterface()) {
			ClassInstance sc = c;

			while ((sc = sc.superClass) != null) {
				if (sc == this) return true;
			}
		} else {
			if (implementers.isEmpty()) return false;

			// check if c directly implements this
			if (implementers.contains(c)) return true;

			// check if a superclass of c directly implements this
			ClassInstance sc = c;

			while ((sc = sc.superClass) != null) {
				if (implementers.contains(sc)) return true; // cls -> this
			}

			// check if c or a superclass of c implements this with one indirection
			sc = c;
			Queue<ClassInstance> toCheck = null;

			do {
				for (ClassInstance iface : sc.getInterfaces()) {
					assert iface != this; // already checked iface directly
					if (iface.interfaces.isEmpty()) continue;
					if (implementers.contains(iface)) return true; // cls -> if -> this

					if (toCheck == null) toCheck = new ArrayDeque<>();

					toCheck.addAll(iface.interfaces);
				}
			} while ((sc = sc.superClass) != null);

			// check if c or a superclass of c implements this with multiple indirections
			if (toCheck != null) {
				while ((sc = toCheck.poll()) != null) {
					for (ClassInstance iface : sc.getInterfaces()) {
						assert iface != this; // already checked

						if (implementers.contains(iface)) return true;

						toCheck.addAll(iface.interfaces);
					}
				}
			}
		}

		return false;
	}

	public ClassInstance getCommonSuperClass(ClassInstance o) {
		if (o == this) return this;
		if (isPrimitive() || o.isPrimitive()) return null;
		if (isAssignableFrom(o)) return this;
		if (o.isAssignableFrom(this)) return o;

		ClassInstance objCls = env.getCreateClassInstance("Ljava/lang/Object;");

		if (!isInterface() && !o.isInterface()) {
			ClassInstance sc = this;

			while ((sc = sc.superClass) != null && sc != objCls) {
				if (sc.isAssignableFrom(o)) return sc;
			}
		}

		if (!interfaces.isEmpty() || !o.interfaces.isEmpty()) {
			List<ClassInstance> ret = new ArrayList<>();
			Queue<ClassInstance> toCheck = new ArrayDeque<>();
			Set<ClassInstance> checked = Util.newIdentityHashSet();
			toCheck.addAll(interfaces);
			toCheck.addAll(o.interfaces);

			ClassInstance cls;

			while ((cls = toCheck.poll()) != null) {
				if (!checked.add(cls)) continue;

				if (cls.isAssignableFrom(o)) {
					ret.add(cls);
				} else {
					toCheck.addAll(cls.interfaces);
				}
			}

			if (!ret.isEmpty()) {
				if (ret.size() >= 1) {
					for (Iterator<ClassInstance> it = ret.iterator(); it.hasNext(); ) {
						cls = it.next();

						for (ClassInstance cls2 : ret) {
							if (cls != cls2 && cls.isAssignableFrom(cls2)) {
								it.remove();
								break;
							}
						}
					}
					// TODO: multiple options..
				}

				return ret.get(0);
			}
		}

		return objCls;
	}

	public void accept(ClassVisitor visitor, NameType nameType) {
		ClassNode cn = getMergedAsmNode();
		if (cn == null) throw new IllegalArgumentException("cls without asm node: "+this);

		synchronized (Util.asmNodeSync) {
			if (nameType != NameType.PLAIN) {
				AsmClassRemapper.process(cn, new AsmRemapper(env, nameType), visitor);
			} else {
				cn.accept(visitor);
			}
		}
	}

	public byte[] serialize(NameType nameType) {
		ClassWriter writer = new ClassWriter(0);
		accept(writer, nameType);

		return writer.toByteArray();
	}

	public ClassInstance equiv() {
		return equiv == null ? equiv = env.getOther().getClsById(id) : equiv;
	}

	public boolean isTopLevel() {
		return outerClass == null && nest == null;
	}

	public ClassInstance getTopLevelClass() {
		if (outerClass != null) {
			return outerClass.getTopLevelClass();
		}
		if (nest != null) {
			return nest.getEnclosingClass().getTopLevelClass();
		}

		return this;
	}

	public boolean hasPotentialNest() {
		if (!isNestable()) {
			return false;
		}
		if (hasNest()) {
			return true;
		}

		return getHighestPotentialScore() > 0;
	}

	public int getHighestPotentialScore() {
		if (potentialScoreDirty) {
			findHighestPotentialScore();
			potentialScoreDirty = false;
		}

		return potentialScore;
	}

	public void markPotentialScoreDirty() {
		if (potentialScoreDirty) {
			return;
		}

		potentialScoreDirty = true;
		hasStaticMembers = null;
		isMethodArgType = null;

		if (nest != null) {
			nest.getEnclosingClass().markPotentialScoreDirty();
		}
		for (ClassInstance nestingClass : nestingClasses) {
			nestingClass.markPotentialScoreDirty();
		}
	}

	private void findHighestPotentialScore() {
		potentialScore = 0;

		if (!isNestable()) {
			return;
		}

		List<NestRankResult> results = NestedClassClassifier.rank(this, env.getClasses(), null);

		if (results.isEmpty()) {
			return;
		}

		results.sort(null);

		int index = results.size() - 1;
		NestRankResult best = results.get(index);

		while (best.isDummy() && index-- > 0) {
			best = results.get(index);
		}

		potentialScore = best.getScore();
	}

	public boolean isNestable() {
		return nestable && isReal();
	}

	public void setNestable(boolean nestable) {
		this.nestable = nestable;

		if (!this.nestable) {
			setNest(null);
		}

		markPotentialScoreDirty();
	}

	public boolean canNestInto(Matchable<?> candidate) {
		if (candidate instanceof ClassInstance) {
			return canNestInto((ClassInstance)candidate);
		}
		if (candidate instanceof MethodInstance) {
			return canNestInto(((MethodInstance)candidate).cls);
		}

		return false;
	}

	public boolean canNestInto(ClassInstance clazz) {
		return this != clazz && !encloses(clazz) && !getChildClasses().contains(clazz);
	}

	public boolean hasNest() {
		return nest != null;
	}

	public Nest getNest() {
		return nest;
	}

	public void setNest(Matchable<?> m, NestType type) {
		setNest((m == null || type == null) ? null : new Nest(m, type));
	}

	public void setNest(Nest n) {
		if (isNestable()) {
			if (n != null) {
				ClassInstance enclClass = n.getEnclosingClass();

				if (encloses(enclClass)) {
					return;
				}
			}

			innerAccess = null;
			innerName = "";
			localPrefix = "";

			if (nest != null) {
				ClassInstance enclClass = nest.getEnclosingClass();

				enclClass.nestingClasses.remove(this);
				enclClass.markPotentialScoreDirty();

				if (nest.getType() == NestType.ANONYMOUS) {
					enclClass.removeAnonymousClass(this);
				}
				if (nest.getType() == NestType.INNER) {
					if (nest.get().getKind() == MatchableKind.METHOD) {
						enclClass.removeLocalClass(this);
					}
				}
			}

			nest = n;

			if (nest != null) {
				ClassInstance enclClass = nest.getEnclosingClass();

				enclClass.nestingClasses.add(this);
				enclClass.markPotentialScoreDirty();

				if (nest.getType() == NestType.ANONYMOUS) {
					enclClass.addAnonymousClass(this);
				}
				if (nest.getType() == NestType.INNER) {
					if (nest.get().getKind() == MatchableKind.METHOD) {
						enclClass.addLocalClass(this);
					}
				}
			}

			markPotentialScoreDirty();

			if (nest != null) {
				setInnerAccess(getAccess());

				if (nest.getType() == NestType.INNER) {
					innerName = getInnerName(getClassName(getName()));
				}
			}
		}
	}

	private void addAnonymousClass(ClassInstance c) {
		anonymousClasses.add(c);
		updateAnonymousClassNames();
	}

	private void removeAnonymousClass(ClassInstance c) {
		anonymousClasses.remove(c);
		updateAnonymousClassNames();
	}

	private void updateAnonymousClassNames() {
		int index = 1;

		for (ClassInstance c : anonymousClasses) {
			c.innerName = Integer.toString(index++);
		}
	}

	private void addLocalClass(ClassInstance c) {
		localClasses.add(c);
		updateLocalClassNames();
	}

	private void removeLocalClass(ClassInstance c) {
		localClasses.remove(c);
		updateLocalClassNames();
	}

	private void updateLocalClassNames() {
		// local class prefixes do not have to be unique
		// but in order to make this this class remappable
		// without issues, we give it a unique number
		int prefix = 1;

		for (ClassInstance c : localClasses) {
			c.localPrefix = Integer.toString(prefix++);
		}
	}

	public Integer getInnerAccess() {
		return innerAccess;
	}

	public void setInnerAccess(int access) {
		innerAccess = access &
			(Opcodes.ACC_PUBLIC |
			Opcodes.ACC_PRIVATE |
			Opcodes.ACC_PROTECTED |
			Opcodes.ACC_STATIC |
			Opcodes.ACC_FINAL |
			Opcodes.ACC_INTERFACE |
			Opcodes.ACC_ABSTRACT |
			Opcodes.ACC_SYNTHETIC |
			Opcodes.ACC_ANNOTATION |
			Opcodes.ACC_ENUM |
			Opcodes.ACC_MODULE);

		if (nest.getType() == NestType.INNER && hasStaticMembers()) {
			innerAccess |= Opcodes.ACC_STATIC;
		}
		if (nest.getType() == NestType.ANONYMOUS) {
			MethodInstance enclMethod = nest.getEnclosingMethod();

			if (enclMethod == null || enclMethod.isStatic()) {
				innerAccess |= Opcodes.ACC_STATIC;
			}

			innerAccess &= ~Opcodes.ACC_FINAL;
		}
	}

	public String getInnerName() {
		return innerName;
	}

	public void setInnerName(String name) {
		innerName = name;
	}

	public String getLocalPrefix() {
		return localPrefix;
	}

	public boolean canBeStatic() {
		if (nest == null || nest.getType() != NestType.INNER) {
			return false;
		}

		ClassInstance enclClass = nest.getEnclosingClass();

		if (!enclClass.isTopLevel() && !enclClass.isActuallyStatic()) {
			return false;
		}

		FieldInstance[] fields = getSyntheticFields();

		for (FieldInstance field : fields) {
			// If this class holds a reference to an enclosing class
			// it cannot be static
			if (field.getType().encloses(this)) {
				return false;
			}
		}

		return true;
	}

	public boolean encloses(Matchable<?> m) {
		for (Matchable<?> p = m.getOwner(); p != null; p = p.getOwner()) {
			if (this == p) {
				return true;
			}
		}
		if (m instanceof ClassInstance) {
			ClassInstance c = (ClassInstance)m;

			if (c.outerClass != null) {
				if (this == c.outerClass || encloses(c.outerClass)) {
					return true;
				}
			}

			Nest nest = ((ClassInstance)m).getNest();

			if (nest != null) {
				m = nest.get();

				if (m == this || encloses(m)) {
					return true;
				}
			}
		}

		return false;
	}

	public boolean canBeAnonymous() {
		return canBeAnonymous && !isInterface() && !hasStaticMembers() && !isMethodArgType();
	}

	public void markNotAnonymous() {
		canBeAnonymous = false;
	}

	public boolean canBeInner() {
		return !isEnum() || (superClass != null && superClass.getName().equals("java/lang/Enum"));
	}

	public boolean isActuallyStatic() {
		Integer access = innerAccess;

		if (innerAccess == null) {
			access = getAccess();
		}

		return (access & Opcodes.ACC_STATIC) != 0;
	}

	public boolean hasStaticMembers() {
		if (hasStaticMembers == null) {
			if (isEnum()) {
				return hasStaticMembers = false;
			}

			for (ClassInstance clazz : innerClasses) {
				if (clazz.isStatic()) {
					return hasStaticMembers = true;
				}
			}
			for (ClassInstance clazz : nestingClasses) {
				if (clazz.isActuallyStatic()) {
					return hasStaticMembers = true;
				}
			}
			for (FieldInstance field : fields) {
				if (field.isStatic() && !field.isSynthetic() && !field.isFinal()) {
					return hasStaticMembers = true;
				}
			}
			for (MethodInstance method : methods) {
				if (method.isStatic() && !method.isSynthetic() && !method.getName().equals("<clinit>")) {
					return hasStaticMembers = true;
				}
			}

			hasStaticMembers = false;
		}

		return hasStaticMembers;
	}

	public boolean isMethodArgType() {
		if (isMethodArgType == null) {
			for (MethodVarInstance var : argTypeRefs) {
				MethodInstance method = var.getMethod();

				if (method.isSynthetic()) {
					continue;
				}
				if (method.getName().equals("<init>")) {
					ClassInstance clazz = method.getCls();
					Nest nest = clazz.getNest();

					if (nest != null && nest.getType() == NestType.ANONYMOUS) {
						continue;
					}
				}

				return isMethodArgType = true;
			}

			isMethodArgType = false;
		}

		return isMethodArgType;
	}

	public boolean hasSyntheticMembers() {
		return hasSyntheticFields() || hasSyntheticMethods();
	}

	public boolean hasSyntheticFields() {
		return syntheticFieldCount > 0;
	}

	public boolean hasSyntheticMethods() {
		return syntheticMethodCount > 0;
	}

	public FieldInstance[] getSyntheticFields() {
		if (syntheticFields == null) {
			syntheticFields = new FieldInstance[syntheticFieldCount];

			int index = 0;
			for (FieldInstance field : fields) {
				if (field.isSynthetic() && !isEnumField(field)) {
					syntheticFields[index++] = field;
				}
			}
		}

		return syntheticFields;
	}

	private boolean isEnumField(FieldInstance field) {
		if (!isEnum() || !field.isSynthetic()) {
			return false;
		}

		ClassInstance type = field.getType();

		if (!type.isArray()) {
			return false;
		}

		return this == type.getElementClass();
	}

	public MethodInstance[] getDeclaredMethods() {
		if (declaredMethods == null) {
			declaredMethods = new MethodInstance[declaredMethodCount];

			int index = 0;
			for (MethodInstance method : methods) {
				if (!method.isSynthetic() && !method.getName().equals("<init>") && !method.getName().equals("<clinit>")) {
					declaredMethods[index++] = method;
				}
			}
		}

		return declaredMethods;
	}

	public MethodInstance[] getInstanceConstructors() {
		if (instanceConstructors == null) {
			instanceConstructors = new MethodInstance[instanceConstructorCount];

			int index = 0;
			for (MethodInstance method : methods) {
				if (!method.isSynthetic() && method.getName().equals("<init>")) {
					instanceConstructors[index++] = method;
				}
			}
		}

		return instanceConstructors;
	}

	public MethodInstance[] getSyntheticMethods() {
		if (syntheticMethods == null) {
			syntheticMethods = new MethodInstance[syntheticMethodCount];

			int index = 0;
			for (MethodInstance method : methods) {
				if (method.isSynthetic() && !method.isBridge()) {
					syntheticMethods[index++] = method;
				}
			}
		}

		return syntheticMethods;
	}

	@Override
	public String toString() {
		return getDisplayName(NameType.PLAIN, true);
	}

	void addMethod(MethodInstance method) {
		if (method == null) throw new NullPointerException("null method");

		methodIdx.put(method.id, method);
		methods = Arrays.copyOf(methods, methods.length + 1);
		methods[methods.length - 1] = method;

		if (method.isSynthetic()) {
			if (!method.isBridge()) {
				syntheticMethodCount++;
			}
		} else if (method.getName().equals("<init>")) {
			instanceConstructorCount++;
		} else if (!method.getName().equals("<clinit>")) {
			declaredMethodCount++;
		}
	}

	void addField(FieldInstance field) {
		if (field == null) throw new NullPointerException("null field");

		fieldIdx.put(field.id, field);
		fields = Arrays.copyOf(fields, fields.length + 1);
		fields[fields.length - 1] = field;

		if (field.isSynthetic() && !isEnumField(field)) {
			syntheticFieldCount++;
		}
	}

	public static String getId(String name) {
		if (name.isEmpty()) throw new IllegalArgumentException("empty class name");
		assert name.charAt(name.length() - 1) != ';' || name.charAt(0) == '[' : name;

		if (name.charAt(0) == '[') {
			assert name.charAt(name.length() - 1) == ';' || name.lastIndexOf('[') == name.length() - 2;

			return name;
		}

		return "L"+name+";";
	}

	public static String getName(String id) {
		return id.startsWith("L") ? id.substring(1, id.length() - 1) : id;
	}

	public static boolean hasOuterName(String name) {
		return hasOuterName(name, name.indexOf('$'));
	}

	private static boolean hasOuterName(String name, int sepPos) {
		return sepPos > 0 && name.charAt(sepPos - 1) != '/'; // ignore names starting with $
	}

	public static String getOuterName(String name) {
		int pos = name.lastIndexOf('$');

		return hasOuterName(name, pos) ? name.substring(0, pos) : null;
	}

	public static String getInnerName(String name) {
		return name.substring(name.lastIndexOf('$') + 1);
	}

	public static String getNestedName(String outerName, String innerName) {
		if (outerName == null || innerName == null) {
			return null;
		} else {
			return outerName + '$' + innerName;
		}
	}

	public static String getPackageName(String name) {
		int pos = name.lastIndexOf('/');

		return pos > 0 ? name.substring(0, pos) : null;
	}

	public static String getClassName(String name) {
		return name.substring(name.lastIndexOf('/') + 1);
	}

	public static final Comparator<ClassInstance> nameComparator = Comparator.comparing(ClassInstance::getName);

	private static final ClassInstance[] noArrays = new ClassInstance[0];
	private static final MethodInstance[] noMethods = new MethodInstance[0];
	private static final FieldInstance[] noFields = new FieldInstance[0];

	final String id;
	private final URI origin;
	final ClassEnv env;
	private ClassNode[] asmNodes;
	private URI[] asmNodeOrigins;
	final boolean nameObfuscated;
	private final boolean input;
	final ClassInstance elementClass; // 0-dim class TODO: improve handling of array classes (references etc.)
	private ClassSignature signature;

	MethodInstance[] methods = noMethods;
	FieldInstance[] fields = noFields;
	final Map<String, MethodInstance> methodIdx = new HashMap<>();
	final Map<String, FieldInstance> fieldIdx = new HashMap<>();

	private ClassInstance[] arrays = noArrays;

	public ClassInstance outerClass;
	final Set<ClassInstance> innerClasses = Util.newIdentityHashSet();

	ClassInstance superClass;
	final Set<ClassInstance> childClasses = Util.newIdentityHashSet();
	final Set<ClassInstance> interfaces = Util.newIdentityHashSet();
	final Set<ClassInstance> implementers = Util.newIdentityHashSet();

	final Set<MethodInstance> methodTypeRefs = Util.newIdentityHashSet();
	final Set<FieldInstance> fieldTypeRefs = Util.newIdentityHashSet();
	final Set<MethodVarInstance> argTypeRefs = Util.newIdentityHashSet();

	final Set<String> strings = new HashSet<>();

	private String tmpName;
	private int uid = -1;

	private String mappedName;
	private String mappedComment;

	private String[] auxName;

	private boolean matchable = true;
	private ClassInstance matchedClass;

	public ClassInstance equiv; // link to equivalent class in other local env

	private int syntheticFieldCount;
	private int declaredMethodCount;
	private int instanceConstructorCount;
	private int syntheticMethodCount;

	private int potentialScore;
	private boolean potentialScoreDirty = true;

	private boolean nestable = true;
	private Nest nest;
	private Integer innerAccess;
	private String innerName = "";
	private String localPrefix = "";
	private boolean canBeAnonymous = true;

	private final Set<ClassInstance> nestingClasses = Util.newIdentityHashSet();
	private final Set<ClassInstance> anonymousClasses = new TreeSet<>((c1, c2) -> {
		String name1 = c1.getName();
		String name2 = c2.getName();
		int l1 = name1.length();
		int l2 = name2.length();

		return l1 == l2 ? Util.compareNatural(name1, name2) : l1 - l2;
	});
	private final Set<ClassInstance> localClasses = new TreeSet<>((c1, c2) -> {
		String name1 = c1.getName();
		String name2 = c2.getName();
		int l1 = name1.length();
		int l2 = name2.length();

		return l1 == l2 ? Util.compareNatural(name1, name2) : l1 - l2;
	});

	private Boolean hasStaticMembers;
	private Boolean isMethodArgType;
	private FieldInstance[] syntheticFields;
	private MethodInstance[] declaredMethods;
	private MethodInstance[] instanceConstructors;
	private MethodInstance[] syntheticMethods;
}
