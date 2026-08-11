package javax.lang.model;

/**
 * Minimal stub of {@code javax.lang.model.SourceVersion} for running ECJ on Android.
 * <p>
 * ECJ 3.20+ references this JDK-only class when determining the latest supported Java
 * source version. Android's runtime does not include it, so providing a small stub
 * avoids {@link NoClassDefFoundError} during on-device compilation.
 */
public enum SourceVersion {
    RELEASE_0,
    RELEASE_1,
    RELEASE_2,
    RELEASE_3,
    RELEASE_4,
    RELEASE_5,
    RELEASE_6,
    RELEASE_7,
    RELEASE_8,
    RELEASE_9,
    RELEASE_10,
    RELEASE_11,
    RELEASE_12,
    RELEASE_13,
    RELEASE_14,
    RELEASE_15,
    RELEASE_16,
    RELEASE_17;

    private static final SourceVersion LATEST = RELEASE_17;

    public static SourceVersion latest() {
        return LATEST;
    }

    public static SourceVersion latestSupported() {
        return LATEST;
    }

    public static boolean isIdentifier(CharSequence name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        char first = name.charAt(0);
        if (!Character.isJavaIdentifierStart(first)) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isName(CharSequence name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        String[] parts = name.toString().split("\\.");
        for (String part : parts) {
            if (!isIdentifier(part)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isKeyword(CharSequence s) {
        return false;
    }

    public static boolean isKeyword(CharSequence s, SourceVersion version) {
        return false;
    }

    public static boolean isName(CharSequence name, SourceVersion version) {
        return isName(name);
    }

    public static boolean isIdentifier(CharSequence name, SourceVersion version) {
        return isIdentifier(name);
    }
}
