package com.r112007.effecteditor.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.r112007.effecteditor.compiler.EffectCompiler;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDT-style completion engine backed by reflection over the actual Mindustry/Arc
 * runtime classes shipped in the APK.
 * <p>
 * Unlike a hand-written keyword list, this engine indexes the real class names,
 * packages, and static members from {@code runtime-libs.jar}, so completions for
 * {@code Color.}, {@code Mathf.}, {@code Pal.}, etc. always match the classes that
 * are actually available at compile time.
 * <p>
 * The class index is built once from the jar entries (fast, no class loading).
 * Static members are reflected lazily and cached.
 */
public class CompletionEngine {

    private static final String TAG = "CompletionEngine";

    private static final List<String> KEYWORDS = Collections.unmodifiableList(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "true", "false", "null", "var"
    ));

    private static final List<String> TOP_LEVEL_FUNCTIONS = Collections.unmodifiableList(Arrays.asList(
            "color", "color(Color)", "color(float,float,float,float)", "alpha",
            "stroke", "line", "lineAngle", "circle", "square", "rect", "poly", "arc",
            "z", "blend", "reset", "scl", "tscl", "flush", "fill", "draw"
    ));

    private static final List<String> SNIPPETS = Collections.unmodifiableList(Arrays.asList(
            "new Effect(60f, e -> {\n    \n});",
            "new Effect(60f, 120f, e -> {\n    \n});"
    ));

    private static final Map<String, String> EFFECT_CONTAINER_MEMBERS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("fin", "float");
        m.put("fout", "float");
        m.put("fslope", "float");
        m.put("fin(Interp)", "float");
        m.put("fout(Interp)", "float");
        m.put("x", "float");
        m.put("y", "float");
        m.put("rotation", "float");
        m.put("id", "int");
        m.put("color", "Color");
        m.put("time", "float");
        m.put("lifetime", "float");
        EFFECT_CONTAINER_MEMBERS = Collections.unmodifiableMap(m);
    }

    private static final Set<String> ALWAYS_IMPORTABLE_PACKAGES = new HashSet<>(Arrays.asList(
            "arc", "mindustry"
    ));

    public static class Suggestion {
        public final String label;
        public final String insert;
        public final String importStmt;
        public final int priority;
        public final String detail;
        /** The type shown in the right column of the IDE-style completion popup. */
        public final String typeText;
        /** Cursor offset relative to the end of {@link #insert}. Negative moves left. */
        public final int cursorOffset;

        public Suggestion(String label, String insert, String importStmt, int priority, String detail) {
            this(label, insert, importStmt, priority, detail, "", 0);
        }

        public Suggestion(String label, String insert, String importStmt, int priority, String detail, String typeText) {
            this(label, insert, importStmt, priority, detail, typeText, 0);
        }

        public Suggestion(String label, String insert, String importStmt, int priority,
                          String detail, String typeText, int cursorOffset) {
            this.label = label;
            this.insert = insert;
            this.importStmt = importStmt;
            this.priority = priority;
            this.detail = detail;
            this.typeText = typeText == null ? "" : typeText;
            this.cursorOffset = cursorOffset;
        }
    }

    private static CompletionEngine instance;
    private static final Object lock = new Object();
    private static Context appContext;

    private final Map<String, List<String>> simpleNameIndex = new HashMap<>();
    private final Set<String> packageIndex = new HashSet<>();
    private final Map<String, List<Suggestion>> memberCache = new HashMap<>();
    private final Map<String, List<Suggestion>> instanceMemberCache = new HashMap<>();
    /** Cache of real parameter names read from the runtime jar's LocalVariableTable. */
    private final Map<String, Map<String, String[]>> paramNameCache = new HashMap<>();
    private volatile boolean ready = false;

    /**
     * Starts building the completion index on a background thread. Should be called
     * during application startup. {@link #get()} returns the engine once it is ready.
     */
    public static void init(Context context) {
        new Thread(() -> {
            synchronized (lock) {
                if (instance == null) {
                    Context appCtx = context.getApplicationContext();
                    appContext = appCtx;
                    instance = new CompletionEngine(appCtx);
                }
            }
        }, "completion-init").start();
    }

    /**
     * Returns the engine if it has been initialized, or null otherwise. This method
     * never blocks the caller, so it is safe to call from the UI thread.
     */
    public static CompletionEngine get() {
        synchronized (lock) {
            return instance;
        }
    }

    /**
     * Records that the user selected a completion item with the given label.
     * Stores selection count (capped at 100) and last selection timestamp in
     * {@code SharedPreferences} named "CompletionHistory".
     */
    public static void recordUsage(String key) {
        if (appContext == null || key == null) return;
        SharedPreferences prefs = appContext.getSharedPreferences("CompletionHistory", Context.MODE_PRIVATE);
        int count = prefs.getInt(key + "_count", 0) + 1;
        if (count > 100) count = 100;
        prefs.edit()
                .putInt(key + "_count", count)
                .putLong(key + "_time", System.currentTimeMillis())
                .apply();
    }

    private static int usageBoost(String key) {
        if (appContext == null || key == null) return 0;
        SharedPreferences prefs = appContext.getSharedPreferences("CompletionHistory", Context.MODE_PRIVATE);
        int count = prefs.getInt(key + "_count", 0);
        long lastTime = prefs.getLong(key + "_time", 0);
        if (count <= 0 || lastTime <= 0) return 0;

        int boost = Math.min(count, 100) * 20;
        long diff = System.currentTimeMillis() - lastTime;
        if (diff <= 60 * 60 * 1000L) {
            boost += 300;
        } else if (diff <= 24 * 60 * 60 * 1000L) {
            boost += 150;
        } else if (diff <= 7 * 24 * 60 * 60 * 1000L) {
            boost += 50;
        }
        return boost;
    }

    private static List<Suggestion> applyUsageBoost(List<Suggestion> list) {
        List<Suggestion> result = new ArrayList<>(list.size());
        for (Suggestion s : list) {
            int boost = usageBoost(s.label);
            if (boost != 0) {
                result.add(new Suggestion(s.label, s.insert, s.importStmt,
                        s.priority + boost, s.detail, s.typeText, s.cursorOffset));
            } else {
                result.add(s);
            }
        }
        return result;
    }

    private static List<Suggestion> sortByPriority(List<Suggestion> list) {
        Collections.sort(list, (a, b) -> Integer.compare(b.priority, a.priority));
        return list;
    }

    private static boolean hasUpperCase(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) return true;
        }
        return false;
    }

    private static boolean camelCaseMatches(String name, String query) {
        if (query == null || query.isEmpty()) return false;
        int i = 0, j = 0;
        while (i < name.length() && j < query.length()) {
            char nc = name.charAt(i);
            char qc = query.charAt(j);
            if (Character.toLowerCase(nc) == Character.toLowerCase(qc)) {
                if (j == 0) {
                    if (i != 0 && !Character.isUpperCase(nc)) return false;
                } else {
                    if (!Character.isUpperCase(nc)) return false;
                }
                j++;
            }
            i++;
        }
        return j == query.length();
    }

    private static int caseBoost(String query, String simpleName) {
        if (query == null || query.isEmpty() || simpleName == null || simpleName.isEmpty()) return 0;
        if (!hasUpperCase(query)) return 0;

        if (simpleName.startsWith(query)) return 10000;
        if (camelCaseMatches(simpleName, query)) return 5000;
        String simpleLower = simpleName.toLowerCase(Locale.ROOT);
        String queryLower = query.toLowerCase(Locale.ROOT);
        if (simpleLower.startsWith(queryLower)) return 1000;
        if (simpleLower.contains(queryLower)) return 100;
        return 0;
    }

    private static boolean isClassContext(String beforeCursor, String query) {
        if (query == null) return false;
        int queryStart = beforeCursor.length() - query.length();
        if (queryStart < 4) return false;
        if (beforeCursor.regionMatches(queryStart - 4, "new ", 0, 4)) {
            int kwStart = queryStart - 4;
            return kwStart == 0 || !Character.isJavaIdentifierPart(beforeCursor.charAt(kwStart - 1));
        }
        if (queryStart >= 7 && beforeCursor.regionMatches(queryStart - 7, "static ", 0, 7)) {
            int kwStart = queryStart - 7;
            return kwStart == 0 || !Character.isJavaIdentifierPart(beforeCursor.charAt(kwStart - 1));
        }
        return false;
    }

    private CompletionEngine(Context context) {
        buildIndex(context);
    }

    public boolean isReady() {
        return ready;
    }

    private void buildIndex(Context context) {
        long start = System.currentTimeMillis();
        try {
            File runtimeJar = EffectCompiler.getRuntimeLibs(context);
            if (runtimeJar == null || !runtimeJar.exists()) {
                Log.w(TAG, "runtime-libs.jar not available");
                return;
            }
            try (JarFile jar = new JarFile(runtimeJar)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(".class") && !name.contains("$")) {
                        String className = name.substring(0, name.length() - 6).replace('/', '.');
                        if (isIndexedPackage(className)) {
                            int lastDot = className.lastIndexOf('.');
                            String simpleName = lastDot >= 0 ? className.substring(lastDot + 1) : className;
                            simpleNameIndex.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(className);
                            if (lastDot > 0) {
                                addPackageAndParents(className.substring(0, lastDot));
                            }
                        }
                    }
                }
            }
            ready = true;
            Log.i(TAG, "Indexed " + simpleNameIndex.size() + " classes in "
                    + (System.currentTimeMillis() - start) + " ms");
        } catch (IOException e) {
            Log.e(TAG, "Failed to build completion index", e);
        }
    }

    private boolean isIndexedPackage(String className) {
        return className.startsWith("arc.") || className.startsWith("mindustry.")
                || className.startsWith("java.lang.") || className.startsWith("java.util.");
    }

    private void addPackageAndParents(String pkg) {
        while (!pkg.isEmpty()) {
            packageIndex.add(pkg);
            int dot = pkg.lastIndexOf('.');
            if (dot < 0) break;
            pkg = pkg.substring(0, dot);
        }
    }

    /**
     * Main entry point. Returns suggestions for the given cursor position.
     */
    public List<Suggestion> complete(String fullText, int cursorPos) {
        if (!ready) {
            return Collections.emptyList();
        }
        String beforeCursor = fullText.substring(0, Math.min(cursorPos, fullText.length()));
        int lineStart = beforeCursor.lastIndexOf('\n') + 1;
        String linePrefix = beforeCursor.substring(lineStart);

        // Import statement context. Trim leading whitespace so indented imports are still
        // recognized, and offer the "static" keyword in a plain import line.
        String trimmedLine = linePrefix.trim();
        if (trimmedLine.startsWith("import ")) {
            String afterImport = trimmedLine.substring("import ".length()).trim();
            if (afterImport.startsWith("static ")) {
                return completeStaticImport(afterImport.substring("static ".length()).trim());
            }
            return completeImport(afterImport);
        }

        String query = extractQuery(beforeCursor);
        int dotIndex = query.lastIndexOf('.');
        if (dotIndex >= 0) {
            String receiver = query.substring(0, dotIndex);
            String memberQuery = query.substring(dotIndex + 1);
            return completeMember(receiver, memberQuery, beforeCursor, fullText);
        }

        boolean classContext = isClassContext(beforeCursor, query);
        return completeTopLevel(query, fullText, cursorPos, classContext);
    }

    private List<Suggestion> completeImport(String partial) {
        List<Suggestion> result = new ArrayList<>();
        String lower = partial.toLowerCase(Locale.ROOT);

        String pkgPrefix = partial;
        if (partial.endsWith(".*")) {
            pkgPrefix = partial.substring(0, partial.length() - 2);
        }
        String finalPkgPrefix = pkgPrefix;

        // Offer the "static" keyword so "import s" can become "import static ".
        if ("static".startsWith(lower)) {
            result.add(new Suggestion("static", "static ", null, 200, "keyword", ""));
        }

        for (String simpleName : simpleNameIndex.keySet()) {
            for (String fullName : simpleNameIndex.get(simpleName)) {
                boolean simpleMatch = simpleName.toLowerCase(Locale.ROOT).startsWith(lower);
                boolean fullMatch = fullName.toLowerCase(Locale.ROOT).startsWith(lower);
                boolean inPackage = !finalPkgPrefix.isEmpty()
                        && fullName.startsWith(finalPkgPrefix + ".")
                        && !fullName.substring(finalPkgPrefix.length() + 1).contains(".");
                if (simpleMatch || fullMatch || inPackage) {
                    int lastDot = fullName.lastIndexOf('.');
                    String pkg = lastDot > 0 ? fullName.substring(0, lastDot) : "";
                    result.add(new Suggestion(fullName, fullName, null, 100, "class", pkg));
                }
            }
        }

        for (String pkg : packageIndex) {
            if (pkg.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(new Suggestion(pkg + ".*", pkg + ".*", null, 90, "package", ""));
            } else if (!finalPkgPrefix.isEmpty() && pkg.startsWith(finalPkgPrefix + ".")) {
                String rest = pkg.substring(finalPkgPrefix.length() + 1);
                if (!rest.contains(".")) {
                    result.add(new Suggestion(pkg + ".*", pkg + ".*", null, 85, "package", ""));
                }
            }
        }

        deduplicate(result);
        return sortByPriority(applyUsageBoost(result));
    }

    private List<Suggestion> completeMember(String receiver, String memberQuery,
                                             String beforeCursor, String fullText) {
        List<Suggestion> result = new ArrayList<>();
        String lower = memberQuery.toLowerCase(Locale.ROOT);

        // Special-case the Effect lambda parameter: its declared type is just
        // "EffectContainer" but we want the hand-written shorthand members too.
        if ("e".equals(receiver) || "effect".equals(receiver) || "container".equals(receiver)) {
            for (String member : EFFECT_CONTAINER_MEMBERS.keySet()) {
                String name = member.contains("(") ? member.substring(0, member.indexOf('(')) : member;
                if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    result.add(new Suggestion(member, receiver + "." + member, null, 250,
                            "field", EFFECT_CONTAINER_MEMBERS.get(member)));
                }
            }
            return result;
        }

        // Try to resolve the receiver as a local variable / field and offer its
        // public instance members (e.g. "Color c = ...; c." -> c.red, c.cpy(), ...).
        // For chained receivers like "Pal.lancerLaser." we recursively resolve the
        // expression type so members of the resulting class are offered.
        String receiverType = receiver.contains(".")
                ? resolveExpressionType(receiver, beforeCursor, fullText)
                : resolveVariableType(receiver, beforeCursor, fullText);
        if (receiverType != null) {
            String fullClassName = resolveClassName(receiverType, fullText);
            if (fullClassName != null) {
                for (Suggestion s : getInstanceMembers(fullClassName)) {
                    String name = s.insert.contains("(") ? s.insert.substring(0, s.insert.indexOf('(')) : s.insert;
                    if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                        String insert = receiver + "." + s.insert;
                        result.add(new Suggestion(s.label, insert, s.importStmt, s.priority, s.detail, s.typeText));
                    }
                }
            }
        }

        // Also allow completing static members when the receiver is a class name
        // (e.g. "Fill.", "Draw.", "Pal.").
        List<String> classes = simpleNameIndex.get(receiver);
        if (classes != null) {
            for (String fullName : classes) {
                List<Suggestion> members = getStaticMembers(fullName);
                for (Suggestion s : members) {
                    String name = s.insert.contains("(") ? s.insert.substring(0, s.insert.indexOf('(')) : s.insert;
                    if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                        // Keep the receiver in the inserted text so "Fill.cir" becomes "Fill.circle("
                        // instead of replacing the whole query with just "circle(".
                        String insert = receiver + "." + s.insert;
                        result.add(new Suggestion(s.label, insert, s.importStmt, s.priority, s.detail, s.typeText));
                    }
                }
            }
        }

        return sortByPriority(applyUsageBoost(result));
    }

    private List<Suggestion> completeTopLevel(String query, String fullText, int cursorPos, boolean classContext) {
        List<Suggestion> result = new ArrayList<>();
        String lower = query.toLowerCase(Locale.ROOT);

        // Completion priority (highest first): keyword > local variable > user field > imported class > unimported class.
        String textBeforeCursor = fullText.substring(0, Math.min(cursorPos, fullText.length()));
        result.addAll(collectVariables(query, textBeforeCursor));
        result.addAll(collectUserFields(query, fullText));

        for (String simpleName : simpleNameIndex.keySet()) {
            int cb = caseBoost(query, simpleName);
            if (simpleName.toLowerCase(Locale.ROOT).startsWith(lower) || cb > 0) {
                for (String fullName : simpleNameIndex.get(simpleName)) {
                    boolean imported = isImported(fullText, fullName);
                    String imp = imported ? null : "import " + fullName + ";";
                    int lastDot = fullName.lastIndexOf('.');
                    String pkg = lastDot > 0 ? fullName.substring(0, lastDot) : "";
                    int priority = imported ? 200 : 100;
                    if (classContext) priority += 5000;
                    priority += cb;
                    result.add(new Suggestion(simpleName + " - " + fullName,
                            simpleName, imp, priority, "class", pkg));
                }
            }
        }

        for (String fn : TOP_LEVEL_FUNCTIONS) {
            if (fn.toLowerCase(Locale.ROOT).startsWith(lower)) {
                String insert = fn.contains("(") ? fn.substring(0, fn.indexOf('(') + 1) : fn + "(";
                if (fn.contains("()") || fn.endsWith("()")) insert = fn;
                result.add(new Suggestion(fn, insert, null, 80, "function", ""));
            }
        }

        for (String kw : KEYWORDS) {
            if (kw.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(new Suggestion(kw, kw + " ", null, 500, "keyword", ""));
            }
        }

        for (String snippet : SNIPPETS) {
            if (lower.isEmpty() || snippet.toLowerCase(Locale.ROOT).contains(lower)) {
                result.add(new Suggestion("Effect snippet", snippet, null, 60, "snippet", ""));
            }
        }

        return sortByPriority(applyUsageBoost(result));
    }

    /**
     * Completion for {@code import static ...} statements.
     */
    private List<Suggestion> completeStaticImport(String partial) {
        List<Suggestion> result = new ArrayList<>();
        String lower = partial.toLowerCase(Locale.ROOT);
        int dotIndex = partial.lastIndexOf('.');

        if (dotIndex < 0) {
            // No dot yet: suggest packages and top-level classes.
            for (String pkg : packageIndex) {
                if (pkg.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    result.add(new Suggestion(pkg, pkg + ".", null, 90, "package", ""));
                }
            }
            for (String simpleName : simpleNameIndex.keySet()) {
                if (simpleName.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    for (String fullName : simpleNameIndex.get(simpleName)) {
                        result.add(new Suggestion(fullName, fullName + ".", null, 100, "class", ""));
                    }
                }
            }
        } else {
            String prefix = partial.substring(0, dotIndex);
            String memberQuery = partial.substring(dotIndex + 1).toLowerCase(Locale.ROOT);

            // Resolve the prefix to a class so we can offer its static members.
            List<String> classCandidates = new ArrayList<>();
            List<String> bySimpleName = simpleNameIndex.get(prefix);
            if (bySimpleName != null) {
                classCandidates.addAll(bySimpleName);
            }
            // Also accept a fully-qualified prefix directly.
            if (classCandidates.isEmpty()) {
                String lastSegment = prefix.contains(".")
                        ? prefix.substring(prefix.lastIndexOf('.') + 1) : prefix;
                List<String> matches = simpleNameIndex.get(lastSegment);
                if (matches != null) {
                    for (String m : matches) {
                        if (m.equals(prefix)) {
                            classCandidates.add(m);
                        }
                    }
                }
            }

            boolean prefixIsPackage = classCandidates.isEmpty();
            for (String fullName : classCandidates) {
                List<Suggestion> members = getStaticMembers(fullName);
                for (Suggestion s : members) {
                    String name = s.insert.contains("(") ? s.insert.substring(0, s.insert.indexOf('(')) : s.insert;
                    if (name.toLowerCase(Locale.ROOT).startsWith(memberQuery)) {
                        result.add(new Suggestion(name, fullName + "." + name, null, 100, "method", s.typeText));
                    }
                }
                result.add(new Suggestion(fullName + ".*", fullName + ".*", null, 80, "package", ""));
            }

            // If the prefix is a package, suggest sub-packages and classes inside it.
            if (prefixIsPackage) {
                Set<String> subPackages = new HashSet<>();
                for (String pkg : packageIndex) {
                    if (pkg.startsWith(prefix + ".") && !pkg.equals(prefix)) {
                        String rest = pkg.substring(prefix.length() + 1);
                        if (!rest.contains(".")) {
                            subPackages.add(rest);
                        }
                    }
                }
                for (String sp : subPackages) {
                    if (sp.toLowerCase(Locale.ROOT).startsWith(memberQuery)) {
                        result.add(new Suggestion(sp, prefix + "." + sp + ".", null, 90, "package", ""));
                    }
                }

                for (String simpleName : simpleNameIndex.keySet()) {
                    for (String fullName : simpleNameIndex.get(simpleName)) {
                        if (fullName.startsWith(prefix + ".") && !fullName.substring(prefix.length() + 1).contains(".")) {
                            String rest = fullName.substring(prefix.length() + 1);
                            if (rest.toLowerCase(Locale.ROOT).startsWith(memberQuery)) {
                                result.add(new Suggestion(rest, fullName + ".", null, 100, "class", fullName));
                            }
                        }
                    }
                }
            }
        }

        deduplicate(result);
        return sortByPriority(applyUsageBoost(result));
    }

    /**
     * Collects local variables declared before the cursor.
     */
    private List<Suggestion> collectVariables(String query, String textBeforeCursor) {
        List<Suggestion> result = new ArrayList<>();
        String lower = query.toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();

        // Match local declarations: "Type name = ...;" or "final Type name = ...;" or "var name = ...;"
        Pattern varPattern = Pattern.compile(
                "(?:^|[{};])\\s*(?:final\\s+)?(?:var|([A-Za-z_$][\\w$]*(?:<[^>]*>)?(?:\\[\\])*))\\s+([a-zA-Z_$][\\w$]*)\\s*(?:=|;)",
                Pattern.MULTILINE);
        Matcher m = varPattern.matcher(textBeforeCursor);
        while (m.find()) {
            String name = m.group(2);
            String type = m.group(1);
            if (type == null || type.isEmpty()) type = "var";
            if (!seen.add(name)) continue;
            if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(new Suggestion(name, name, null, 400, "variable", type));
            }
        }

        // Lambda parameters: e -> or (x, y) -> or (Type x, Type y) ->
        Pattern lambdaPattern = Pattern.compile("\\(([^)]*)\\)\\s*->|(\\w+)\\s*->");
        Matcher lm = lambdaPattern.matcher(textBeforeCursor);
        while (lm.find()) {
            if (lm.group(2) != null) {
                String name = lm.group(2);
                if (!seen.add(name)) continue;
                if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    result.add(new Suggestion(name, name, null, 400, "variable", "EffectContainer"));
                }
            } else {
                String params = lm.group(1);
                for (String param : params.split(",")) {
                    param = param.trim();
                    if (param.isEmpty()) continue;
                    String name = param;
                    int space = param.lastIndexOf(' ');
                    String type = "";
                    if (space >= 0) {
                        type = param.substring(0, space).trim();
                        name = param.substring(space + 1).trim();
                    }
                    if (!seen.add(name)) continue;
                    if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                        result.add(new Suggestion(name, name, null, 400, "variable", type));
                    }
                }
            }
        }

        return result;
    }

    /**
     * Collects user-declared top-level fields (e.g. "public static Rand rand = new Rand();").
     */
    private List<Suggestion> collectUserFields(String query, String fullText) {
        List<Suggestion> result = new ArrayList<>();
        String lower = query.toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();

        Pattern fieldPattern = Pattern.compile(
                "^[ \\t]*(?:public|private|protected)?\\s*(?:static\\s+)?(?:final\\s+)?(?:[A-Za-z_$][\\w$]*(?:<[^>]*>)?(?:\\[\\])*\\s+)?([a-zA-Z_$][\\w$]*)\\s*(?:=|;)",
                Pattern.MULTILINE);
        Matcher m = fieldPattern.matcher(fullText);
        while (m.find()) {
            String name = m.group(1);
            if (!seen.add(name)) continue;
            if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(new Suggestion(name, name, null, 300, "field", ""));
            }
        }

        return result;
    }

    /**
     * Tries to find the declared type of a local variable, lambda parameter, or
     * user field with the given name. Returns the simple or fully-qualified type
     * name, or null if it cannot be determined.
     */
    private String resolveVariableType(String name, String textBeforeCursor, String fullText) {
        // Local declarations before the cursor: "Type name = ...;" / "var name = ...;"
        Pattern varPattern = Pattern.compile(
                "(?:^|[{};])\\s*(?:final\\s+)?(?:var|([A-Za-z_$][\\w$]*(?:<[^>]*>)?(?:\\[\\])*))\\s+([a-zA-Z_$][\\w$]*)\\s*(?:=|;)",
                Pattern.MULTILINE);
        Matcher m = varPattern.matcher(textBeforeCursor);
        String type = null;
        while (m.find()) {
            if (name.equals(m.group(2))) {
                type = m.group(1);
                if (type == null || type.isEmpty()) type = "var";
            }
        }
        if (type != null) {
            return type;
        }

        // Lambda parameters: "e ->" or "(x, y) ->" or "(Type x, Type y) ->"
        Pattern lambdaPattern = Pattern.compile("\\(([^)]*)\\)\\s*->|(\\w+)\\s*->");
        Matcher lm = lambdaPattern.matcher(textBeforeCursor);
        while (lm.find()) {
            if (lm.group(2) != null && name.equals(lm.group(2))) {
                return "EffectContainer";
            } else if (lm.group(1) != null) {
                String[] params = lm.group(1).split(",");
                for (String param : params) {
                    param = param.trim();
                    if (param.isEmpty()) continue;
                    int space = param.lastIndexOf(' ');
                    String pName = space >= 0 ? param.substring(space + 1).trim() : param;
                    String pType = space >= 0 ? param.substring(0, space).trim() : "";
                    if (name.equals(pName)) {
                        return pType.isEmpty() ? "EffectContainer" : pType;
                    }
                }
            }
        }

        // User-declared helper fields anywhere in the snippet.
        Pattern fieldPattern = Pattern.compile(
                "^[ \\t]*(?:public|private|protected)?\\s*(?:static\\s+)?(?:final\\s+)?([A-Za-z_$][\\w$]*(?:<[^>]*>)?(?:\\[\\])*)\\s+([a-zA-Z_$][\\w$]*)\\s*(?:=|;)",
                Pattern.MULTILINE);
        Matcher fm = fieldPattern.matcher(fullText);
        while (fm.find()) {
            if (name.equals(fm.group(2))) {
                return fm.group(1).trim();
            }
        }

        return null;
    }

    /**
     * Resolves a simple type name (e.g. "Color") to a fully-qualified class name
     * using explicit imports, wildcard imports, and the indexed runtime classes.
     */
    private String resolveClassName(String simpleType, String fullText) {
        if (simpleType == null) return null;
        simpleType = simpleType.trim();
        if (simpleType.isEmpty() || "var".equals(simpleType)) return null;

        // Strip generics ("Seq<Effect>" -> "Seq") and array brackets ("Color[]" -> "Color").
        int genStart = simpleType.indexOf('<');
        if (genStart >= 0) {
            simpleType = simpleType.substring(0, genStart);
        }
        while (simpleType.endsWith("[]")) {
            simpleType = simpleType.substring(0, simpleType.length() - 2);
        }
        simpleType = simpleType.trim();
        if (simpleType.isEmpty()) return null;

        // Nested class references (source syntax "Outer.Inner") need "$" in the binary name.
        if (simpleType.contains(".")) {
            String nested = simpleType.replace('.', '$');
            String lastSegment = simpleType.substring(simpleType.lastIndexOf('.') + 1);
            List<String> candidates = simpleNameIndex.get(lastSegment);
            if (candidates != null) {
                for (String c : candidates) {
                    if (c.equals(simpleType) || c.equals(nested) || c.replace('$', '.').equals(simpleType)) {
                        return c;
                    }
                    // Handle partially-qualified nested names like "Effect.EffectContainer".
                    if (c.endsWith("$" + lastSegment) && simpleType.indexOf('.') > 0) {
                        String outer = simpleType.substring(0, simpleType.indexOf('.'));
                        if (c.contains("." + outer + "$") || c.endsWith(outer + "$" + lastSegment)) {
                            return c;
                        }
                    }
                }
            }
            // Could be a fully-qualified name not present in the index (e.g. java.lang).
            return simpleType;
        }

        // Hard-coded mappings for types that are not obvious from a simple name.
        // Nested classes use "$" because Class.forName expects the binary name.
        if ("EffectContainer".equals(simpleType)) {
            return "mindustry.entities.Effect$EffectContainer";
        }

        // Primitives and void have no class members we can offer.
        switch (simpleType) {
            case "void":
            case "boolean": case "byte": case "char": case "short":
            case "int": case "long": case "float": case "double":
                return null;
        }

        // Direct import: import some.pkg.Color;
        Pattern importPattern = Pattern.compile("^[ \\t]*import[ \\t]+([^;]+);", Pattern.MULTILINE);
        Matcher matcher = importPattern.matcher(fullText);
        while (matcher.find()) {
            String imp = matcher.group(1).trim();
            if (imp.endsWith("." + simpleType)) {
                return imp;
            }
            if (imp.endsWith(".*")) {
                String pkg = imp.substring(0, imp.length() - 2);
                List<String> candidates = simpleNameIndex.get(simpleType);
                if (candidates != null) {
                    for (String c : candidates) {
                        if (c.startsWith(pkg + ".")) return c;
                    }
                }
            }
        }

        // Fall back to the indexed runtime classes.
        List<String> candidates = simpleNameIndex.get(simpleType);
        if (candidates != null && !candidates.isEmpty()) {
            if (candidates.size() == 1) return candidates.get(0);
            for (String c : candidates) {
                if (c.startsWith("java.lang.")) return c;
            }
            return candidates.get(0);
        }
        return null;
    }

    /**
     * Resolves the declared type of an expression. Handles simple names (variables,
     * class names) and chained field access such as "Pal.lancerLaser" or "e.color".
     * Method calls are not followed because parentheses break the simple query extraction.
     */
    private String resolveExpressionType(String expr, String beforeCursor, String fullText) {
        if (expr == null || expr.isEmpty()) return null;
        expr = expr.trim();
        if (expr.contains(".")) {
            int lastDot = expr.lastIndexOf('.');
            String parent = expr.substring(0, lastDot);
            String member = expr.substring(lastDot + 1);
            String parentType = resolveExpressionType(parent, beforeCursor, fullText);
            if (parentType == null) return null;
            String parentClass = resolveClassName(parentType, fullText);
            if (parentClass == null) return null;
            return resolveMemberType(parentClass, member);
        }

        // Simple name: prefer a variable / field, otherwise treat as a class name.
        String varType = resolveVariableType(expr, beforeCursor, fullText);
        if (varType != null) return varType;
        List<String> classes = simpleNameIndex.get(expr);
        if (classes != null && !classes.isEmpty()) {
            return classes.get(0);
        }
        return null;
    }

    /**
     * Returns the declared type of a public field or the return type of a public method.
     */
    private String resolveMemberType(String fullClassName, String memberName) {
        int paren = memberName.indexOf('(');
        if (paren >= 0) memberName = memberName.substring(0, paren);
        try {
            Class<?> clazz = Class.forName(fullClassName, false,
                    CompletionEngine.class.getClassLoader());
            String result = resolveMemberTypeInClass(clazz, memberName);
            if (result != null) return result;
            Class<?> sup = clazz.getSuperclass();
            while (sup != null && !sup.getName().equals("java.lang.Object")) {
                result = resolveMemberTypeInClass(sup, memberName);
                if (result != null) return result;
                sup = sup.getSuperclass();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not resolve member type: " + fullClassName + "." + memberName);
        }
        return null;
    }

    private String resolveMemberTypeInClass(Class<?> clazz, String memberName) {
        for (Field f : clazz.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers()) && f.getName().equals(memberName)) {
                return f.getType().getName();
            }
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && m.getName().equals(memberName)) {
                return m.getReturnType().getName();
            }
        }
        return null;
    }

    private List<Suggestion> getInstanceMembers(String fullClassName) {
        List<Suggestion> cached = instanceMemberCache.get(fullClassName);
        if (cached != null) return cached;

        List<Suggestion> result = new ArrayList<>();
        try {
            Class<?> clazz = Class.forName(fullClassName, false,
                    CompletionEngine.class.getClassLoader());
            if (!Modifier.isPublic(clazz.getModifiers())) {
                instanceMemberCache.put(fullClassName, result);
                return result;
            }

            collectInstanceMembers(clazz, result);
            Class<?> superClass = clazz.getSuperclass();
            while (superClass != null && !superClass.getName().equals("java.lang.Object")) {
                collectInstanceMembers(superClass, result);
                superClass = superClass.getSuperclass();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not reflect instance members of " + fullClassName + ": " + t.getMessage());
        }

        deduplicate(result);
        instanceMemberCache.put(fullClassName, result);
        return result;
    }

    private void collectInstanceMembers(Class<?> clazz, List<Suggestion> out) {
        for (Field field : clazz.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (Modifier.isPublic(mod) && !Modifier.isStatic(mod)) {
                out.add(new Suggestion(field.getName(), field.getName(), null, 100,
                        "field", simpleTypeName(field.getType())));
            }
        }
        for (Method method : clazz.getDeclaredMethods()) {
            int mod = method.getModifiers();
            if (Modifier.isPublic(mod) && !Modifier.isStatic(mod)) {
                out.add(buildMethodSuggestion(method));
            }
        }
    }

    private List<Suggestion> getStaticMembers(String fullClassName) {
        List<Suggestion> cached = memberCache.get(fullClassName);
        if (cached != null) return cached;

        List<Suggestion> result = new ArrayList<>();
        try {
            Class<?> clazz = Class.forName(fullClassName, false,
                    CompletionEngine.class.getClassLoader());
            if (!Modifier.isPublic(clazz.getModifiers())) {
                memberCache.put(fullClassName, result);
                return result;
            }

            collectStaticMembers(clazz, result);
            Class<?> superClass = clazz.getSuperclass();
            while (superClass != null && !superClass.getName().equals("java.lang.Object")) {
                collectStaticMembers(superClass, result);
                superClass = superClass.getSuperclass();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not reflect members of " + fullClassName + ": " + t.getMessage());
        }

        deduplicate(result);
        memberCache.put(fullClassName, result);
        return result;
    }

    private void collectStaticMembers(Class<?> clazz, List<Suggestion> out) {
        for (Field field : clazz.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (Modifier.isPublic(mod) && Modifier.isStatic(mod)) {
                out.add(new Suggestion(field.getName(), field.getName(), null, 100,
                        "field", simpleTypeName(field.getType())));
            }
        }
        for (Method method : clazz.getDeclaredMethods()) {
            int mod = method.getModifiers();
            if (Modifier.isPublic(mod) && Modifier.isStatic(mod)) {
                out.add(buildMethodSuggestion(method));
            }
        }
    }

    private Suggestion buildMethodSuggestion(Method method) {
        String name = method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        String[] paramNames = resolveParameterNames(method);

        // Build a readable parameter list that includes both type and name.
        StringBuilder paramsLabel = new StringBuilder();
        StringBuilder insert = new StringBuilder();
        insert.append(name).append("(");
        for (int i = 0; i < paramTypes.length; i++) {
            String typeName = simpleTypeName(paramTypes[i]);
            String pName = paramNames[i];
            if (i > 0) {
                paramsLabel.append(", ");
                insert.append(", ");
            }
            paramsLabel.append(typeName).append(" ").append(pName);
            insert.append(pName);
        }

        // Label shows the full signature with parameter names for clarity.
        StringBuilder label = new StringBuilder();
        label.append(name).append("(").append(paramsLabel).append(")");
        Class<?> ret = method.getReturnType();
        if (ret != void.class) {
            label.append(" : ").append(simpleTypeName(ret));
        }
        insert.append(")");

        int cursorOffset = paramNames.length > 0 ? -1 : 0;
        return new Suggestion(label.toString(), insert.toString(), null, 100,
                "method", simpleTypeName(ret), cursorOffset);
    }

    /**
     * Resolves meaningful parameter names for a method.
     * <ol>
     *   <li>First tries the runtime jar's LocalVariableTable via ASM (most accurate).</li>
     *   <li>Falls back to Java reflection {@code Parameter.getName()}.</li>
     *   <li>If both yield generic names like arg0/arg1, derives a name from the type.</li>
     * </ol>
     */
    private String[] resolveParameterNames(Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        String[] names = new String[paramTypes.length];

        // Try ASM first: the runtime jar contains LocalVariableTable info.
        String className = method.getDeclaringClass().getName();
        String methodKey = method.getName() + Type.getMethodDescriptor(method);
        Map<String, String[]> classCache = paramNameCache.get(className);
        if (classCache == null) {
            classCache = loadParameterNamesFromJar(className);
            paramNameCache.put(className, classCache);
        }
        String[] asmNames = classCache.get(methodKey);
        if (asmNames != null && asmNames.length == paramTypes.length) {
            for (int i = 0; i < asmNames.length; i++) {
                names[i] = sanitizeParamName(asmNames[i], paramTypes[i], i);
            }
            return names;
        }

        // Fallback to reflection (usually arg0/arg1 unless compiled with -parameters).
        java.lang.reflect.Parameter[] reflectNames = method.getParameters();
        for (int i = 0; i < paramTypes.length; i++) {
            String raw = i < reflectNames.length ? reflectNames[i].getName() : null;
            names[i] = sanitizeParamName(raw, paramTypes[i], i);
        }
        return names;
    }

    private Map<String, String[]> loadParameterNamesFromJar(String className) {
        Map<String, String[]> result = new HashMap<>();
        if (appContext == null) return result;
        try {
            File runtimeJar = EffectCompiler.getRuntimeLibs(appContext);
            if (runtimeJar == null || !runtimeJar.exists()) return result;

            try (JarFile jar = new JarFile(runtimeJar)) {
                String entryName = className.replace('.', '/') + ".class";
                JarEntry entry = jar.getJarEntry(entryName);
                if (entry == null) return result;

                try (InputStream in = jar.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(in);
                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String mname, String descriptor,
                                                          String signature, String[] exceptions) {
                            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                            int paramCount = Type.getArgumentTypes(descriptor).length;
                            String[] names = new String[paramCount];
                            Arrays.fill(names, null);

                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitLocalVariable(String varName, String varDesc,
                                                                String varSignature, Label start,
                                                                Label end, int index) {
                                    int argIndex = isStatic ? index : index - 1;
                                    if (argIndex >= 0 && argIndex < paramCount) {
                                        names[argIndex] = varName;
                                    }
                                }

                                @Override
                                public void visitEnd() {
                                    result.put(mname + descriptor, names);
                                }
                            };
                        }
                    }, 0);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not load parameter names for " + className + ": " + t.getMessage());
        }
        return result;
    }

    private boolean isGenericArgName(String name) {
        if (name == null || name.isEmpty()) return true;
        if ("arg".equals(name)) return true;
        return name.matches("arg\\d+") || name.matches("var\\d+");
    }

    private String sanitizeParamName(String raw, Class<?> type, int index) {
        if (!isGenericArgName(raw)) return raw;
        return fallbackParamName(type, index);
    }

    private String fallbackParamName(Class<?> type, int index) {
        String base = fallbackParamNameBase(type);
        return index == 0 ? base : base + index;
    }

    private String fallbackParamNameBase(Class<?> type) {
        if (type == int.class || type == Integer.class) return "i";
        if (type == float.class || type == Float.class) return "f";
        if (type == double.class || type == Double.class) return "d";
        if (type == long.class || type == Long.class) return "l";
        if (type == boolean.class || type == Boolean.class) return "b";
        if (type == byte.class || type == Byte.class) return "b";
        if (type == short.class || type == Short.class) return "s";
        if (type == char.class || type == Character.class) return "c";
        if (type == String.class) return "str";

        String name = simpleTypeName(type);
        if (name.endsWith("[]")) {
            name = name.substring(0, name.length() - 2) + "s";
        }
        if (name.isEmpty()) return "arg";
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private String simpleTypeName(Class<?> type) {
        if (type.isArray()) {
            return simpleTypeName(type.getComponentType()) + "[]";
        }
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private boolean isImported(String text, String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        if (lastDot < 0) return true;
        String pkg = fullName.substring(0, lastDot);
        if (pkg.equals("java.lang")) return true;
        return text.contains("import " + fullName + ";")
                || text.contains("import " + pkg + ".*;");
    }

    private String extractQuery(String text) {
        int end = text.length();
        int start = end;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.' || c == '"') {
                start--;
            } else {
                break;
            }
        }
        return text.substring(start, end);
    }

    private void deduplicate(List<Suggestion> list) {
        Set<String> seen = new HashSet<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            String key = list.get(i).label;
            if (!seen.add(key)) {
                list.remove(i);
            }
        }
    }
}
