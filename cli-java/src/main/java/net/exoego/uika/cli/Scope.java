package net.exoego.uika.cli;

/**
 * Resolution scope layered over several indexes, first layer wins.
 *
 * <p>Real JVM linking runs against the whole runtime classpath, so check resolves against
 * "library + scanned classpath" rather than the library alone. That avoids false positives
 * for a move to another artifact or a copy bundled into a fat JAR.
 */
final class Scope {
    enum MemberKind {
        METHOD,
        FIELD
    }

    enum Resolution {
        FOUND,
        NOT_FOUND,
        /** Resolution reached a type outside the scope, so existence cannot be proven. */
        UNKNOWN
    }

    /** {@link #resolveMember} results that are not a found member. Found is {@code owner << 16 | access}. */
    static final long NOT_FOUND = -1L;
    static final long UNKNOWN = -2L;

    private static final int OBJECT = Intern.intern("java/lang/Object");
    private static final int INIT = Intern.intern("<init>");
    private static final int CLINIT = Intern.intern("<clinit>");
    private static final long[] OBJECT_METHODS = {
        MemberKey.of("getClass", "()Ljava/lang/Class;"),
        MemberKey.of("hashCode", "()I"),
        MemberKey.of("equals", "(Ljava/lang/Object;)Z"),
        MemberKey.of("clone", "()Ljava/lang/Object;"),
        MemberKey.of("toString", "()Ljava/lang/String;"),
        MemberKey.of("notify", "()V"),
        MemberKey.of("notifyAll", "()V"),
        MemberKey.of("wait", "()V"),
        MemberKey.of("wait", "(J)V"),
        MemberKey.of("wait", "(JI)V"),
        MemberKey.of("finalize", "()V"),
    };

    private final ApiIndex[] layers;

    Scope(ApiIndex... layers) {
        this.layers = layers;
    }

    static int objectSym() {
        return OBJECT;
    }

    /** The 11 java/lang/Object methods are built in: Object itself is rarely in scope. */
    static boolean isObjectMethod(long key) {
        for (long m : OBJECT_METHODS) {
            if (m == key) {
                return true;
            }
        }
        return false;
    }

    static boolean isFound(long resolved) {
        return resolved >= 0;
    }

    static int foundOwner(long resolved) {
        return (int) (resolved >>> 16);
    }

    static int foundAccess(long resolved) {
        return (int) (resolved & 0xffff);
    }

    private static long found(int owner, int access) {
        return (long) owner << 16 | access;
    }

    /** {@code layer << 32 | entry} of the first layer defining the class, or -1. */
    private long locate(int className) {
        for (int l = 0; l < layers.length; l++) {
            int entry = layers[l].entry(className);
            if (entry >= 0) {
                return (long) l << 32 | entry;
            }
        }
        return -1;
    }

    boolean containsClass(int className) {
        return locate(className) >= 0;
    }

    /** Class access flags, or -1 when the class is in no layer. */
    int classAccess(int className) {
        long at = locate(className);
        return at < 0 ? -1 : layers[(int) (at >>> 32)].accessOf((int) at);
    }

    /** The index defining the class, or null. Pair with {@link #entryOf}. */
    ApiIndex layerOf(int className) {
        long at = locate(className);
        return at < 0 ? null : layers[(int) (at >>> 32)];
    }

    /**
     * NestHost of a class in scope. {@link Intern#NONE} covers both "not in scope" and "no
     * attribute": either way the caller falls back to the class hosting itself.
     */
    int classNestHost(int className) {
        long at = locate(className);
        return at < 0 ? Intern.NONE : layers[(int) (at >>> 32)].nestHostOf((int) at);
    }

    /** Access flags of a method DECLARED on the class (not inherited), or -1. */
    int directMethodAccess(int className, long key) {
        long at = locate(className);
        return at < 0 ? -1 : layers[(int) (at >>> 32)].findMethod((int) at, key);
    }

    Resolution resolve(int owner, long key, MemberKind kind) {
        long resolved = resolveMember(owner, key, kind);
        if (resolved >= 0) {
            return Resolution.FOUND;
        }
        return resolved == NOT_FOUND ? Resolution.NOT_FOUND : Resolution.UNKNOWN;
    }

    /** Simplified JVMS 5.4.3.2 / 5.4.3.3. Returns a found member, {@link #NOT_FOUND} or {@link #UNKNOWN}. */
    long resolveMember(int owner, long key, MemberKind kind) {
        // Constructors and the class initializer are not inherited: walking the chain for
        // them would bind a removed constructor to a superclass copy and misreport a
        // NoSuchMethodError as access-narrowed.
        if (kind == MemberKind.METHOD && isConstructor(key)) {
            long at = locate(owner);
            if (at < 0) {
                return UNKNOWN;
            }
            int access = layers[(int) (at >>> 32)].findMethod((int) at, key);
            return access < 0 ? NOT_FOUND : found(owner, access);
        }
        return kind == MemberKind.FIELD ? resolveField(owner, key, new IntSet()) : resolveMethod(owner, key);
    }

    private static boolean isConstructor(long key) {
        int name = MemberKey.name(key);
        return name == INIT || name == CLINIT;
    }

    /**
     * JVMS 5.4.3.2: the class, then its superinterfaces (recursively, before the superclass),
     * then the superclass. An Unknown interface branch bails before the superclass, since the
     * unseen higher-priority type could shadow a superclass field.
     */
    private long resolveField(int className, long key, IntSet seen) {
        if (className == OBJECT) {
            return NOT_FOUND;
        }
        if (!seen.add(className)) {
            return NOT_FOUND;
        }
        long at = locate(className);
        if (at < 0) {
            return UNKNOWN;
        }
        ApiIndex index = layers[(int) (at >>> 32)];
        int entry = (int) at;
        int access = index.findField(entry, key);
        if (access >= 0) {
            return found(className, access);
        }
        int interfaces = index.interfaceCount(entry);
        for (int k = 0; k < interfaces; k++) {
            long resolved = resolveField(index.interfaceAt(entry, k), key, seen);
            if (resolved != NOT_FOUND) {
                return resolved;
            }
        }
        int superName = index.superOf(entry);
        return superName == Intern.NONE ? NOT_FOUND : resolveField(superName, key, seen);
    }

    /**
     * JVMS 5.4.3.3: the full superclass chain first, then superinterfaces. The interface
     * phase is first-match rather than strict maximally-specific selection, which is enough
     * for existence and access.
     */
    private long resolveMethod(int owner, long key) {
        int[] pending = new int[8];
        int pendingCount = 0;
        IntSet seen = new IntSet();
        int current = owner;
        while (current != Intern.NONE) {
            if (!seen.add(current)) {
                break;
            }
            if (current == OBJECT) {
                if (isObjectMethod(key)) {
                    return found(current, Acc.PUBLIC);
                }
                break;
            }
            long at = locate(current);
            if (at < 0) {
                // The superclass chain has priority over interfaces, so a break in it could
                // hide a superclass method.
                return UNKNOWN;
            }
            ApiIndex index = layers[(int) (at >>> 32)];
            int entry = (int) at;
            int access = index.findMethod(entry, key);
            if (access >= 0) {
                return found(current, access);
            }
            int interfaces = index.interfaceCount(entry);
            if (pendingCount + interfaces > pending.length) {
                pending = java.util.Arrays.copyOf(pending, Math.max(pending.length * 2, pendingCount + interfaces));
            }
            for (int k = 0; k < interfaces; k++) {
                pending[pendingCount++] = index.interfaceAt(entry, k);
            }
            current = index.superOf(entry);
        }
        IntSet interfaceSeen = new IntSet();
        boolean unknown = false;
        for (int k = 0; k < pendingCount; k++) {
            long resolved = resolveInterfaceMethod(pending[k], key, interfaceSeen);
            if (resolved >= 0) {
                return resolved;
            }
            unknown |= resolved == UNKNOWN;
        }
        return unknown ? UNKNOWN : NOT_FOUND;
    }

    private long resolveInterfaceMethod(int iface, long key, IntSet seen) {
        if (!seen.add(iface)) {
            return NOT_FOUND;
        }
        long at = locate(iface);
        if (at < 0) {
            return UNKNOWN;
        }
        ApiIndex index = layers[(int) (at >>> 32)];
        int entry = (int) at;
        int access = index.findMethod(entry, key);
        if (access >= 0) {
            return found(iface, access);
        }
        boolean unknown = false;
        int interfaces = index.interfaceCount(entry);
        for (int k = 0; k < interfaces; k++) {
            long resolved = resolveInterfaceMethod(index.interfaceAt(entry, k), key, seen);
            if (resolved >= 0) {
                return resolved;
            }
            unknown |= resolved == UNKNOWN;
        }
        return unknown ? UNKNOWN : NOT_FOUND;
    }

    /** Entry handle of the class inside {@link #layerOf}, or -1. */
    int entryOf(int className) {
        long at = locate(className);
        return at < 0 ? -1 : (int) at;
    }
}
