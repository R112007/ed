package com.r112007.effecteditor.compiler;

import android.content.Context;
import android.util.Log;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;

import org.eclipse.jdt.internal.compiler.batch.Main;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mindustry.entities.Effect;

/**
 * Compiles the user's {@code new Effect(...)} snippet into a real
 * {@link Effect} instance.
 * <p>
 * Strategy:
 * <ol>
 * <li>Wrap the snippet into a complete Java class.</li>
 * <li>Compile the source with ECJ (Eclipse Compiler for Java) using the runtime
 * library jar
 * shipped in assets as the classpath.</li>
 * <li>Dex the resulting .class with R8 D8.</li>
 * <li>Load the dex via {@link dalvik.system.DexClassLoader} and read the static
 * field.</li>
 * </ol>
 * <p>
 * The wrapped source is compiled with ECJ at Java 17. ECJ 3.33.0 is pinned
 * specifically
 * because it supports modern language features (pattern matching instanceof,
 * var, text blocks)
 * without referencing JDK 9+ APIs such as {@code java.lang.Runtime.Version}
 * that are missing
 * on Android.
 */
public class EffectCompiler {

    private static final String TAG = "EffectCompiler";
    private static final String RUNTIME_LIBS_ASSET = "runtime-libs.jar";
    private static final String ANDROID_JAR_ASSET = "android.jar";
    // Lambda support stubs from Android build-tools. ECJ needs these to compile
    // lambda expressions because android.jar omits
    // java.lang.invoke.LambdaMetafactory.
    private static final String LAMBDA_STUBS_ASSET = "core-lambda-stubs.jar";
    // Java 17 gives users pattern-matching instanceof, var, etc. while staying on
    // an
    // ECJ version known not to call missing Android JDK APIs.
    private static final String SOURCE_TARGET = "8";

    // Allowed import roots. Imports outside these packages are rejected to avoid
    // compilation errors from unavailable Android APIs.
    private static final String[] ALLOWED_IMPORT_PREFIXES = {
            "arc.",
            "mindustry.",
            "java.lang.",
            "java.util.",
            "java.io."
    };

    /**
     * Cached copies of the large asset jars. They are extracted once and reused so
     * that
     * subsequent compilations do not pay the asset-extraction cost every time.
     */
    private static File cachedRuntimeJar;
    private static File cachedAndroidJar;
    private static File cachedLambdaStubsJar;

    /**
     * Exposes the extracted runtime-libs.jar so that other components (e.g. the
     * completion engine) can scan the classes that are available to ECJ.
     */
    public static synchronized File getRuntimeLibs(Context context) throws IOException {
        return getOrExtractRuntimeLibs(context);
    }

    public static Effect compile(String userCode, Context context) throws Exception {
        validateTextures(userCode, context);

        File workDir = new File(context.getCodeCacheDir(), "effect_compile" + System.currentTimeMillis());
        if (!workDir.mkdirs()) {
            throw new IOException("Cannot create compile work dir");
        }

        File srcDir = new File(workDir, "src");
        File classDir = new File(workDir, "classes");
        File dexDir = new File(workDir, "dex");
        srcDir.mkdirs();
        classDir.mkdirs();
        dexDir.mkdirs();

        String className = "Effect_" + System.currentTimeMillis();
        File srcFile = new File(srcDir, className + ".java");
        GeneratedSource generated = writeSource(srcFile, className, userCode);

        File runtimeJar = getOrExtractRuntimeLibs(context);
        File androidJar = getOrExtractAndroidJar(context);
        File lambdaStubsJar = getOrExtractLambdaStubs(context);
        // ECJ does not support -bootclasspath at compliance level 9+.
        // Put android.jar and lambda stubs on the regular classpath instead.
        // Lambda stubs are required because android.jar omits LambdaMetafactory.
        String classpath = runtimeJar.getAbsolutePath()
                + File.pathSeparator + androidJar.getAbsolutePath()
                + File.pathSeparator + lambdaStubsJar.getAbsolutePath();

        compileWithEcj(generated.file, classDir, classpath, generated.lineMapping);

        File classFile = new File(classDir, className + ".class");
        if (!classFile.exists()) {
            throw new RuntimeException("ECJ did not produce a class file");
        }

        File dexFile = new File(dexDir, "classes.dex");
        dexWithR8(classFile, dexFile, androidJar, runtimeJar);

        // DexClassLoader works on API 21+, unlike InMemoryDexClassLoader which needs
        // API 26+.
        File optDir = new File(workDir, "opt");
        optDir.mkdirs();
        dalvik.system.DexClassLoader loader = new dalvik.system.DexClassLoader(
                dexFile.getAbsolutePath(),
                optDir.getAbsolutePath(),
                null,
                context.getClassLoader());

        Class<?> clazz = loader.loadClass(className);
        return (Effect) clazz.getField("instance").get(null);
    }

    /**
     * Wrapper around the generated source file and a mapping from generated line
     * numbers back to the user's original line numbers. This lets us report
     * compilation errors on the lines the user actually wrote.
     */
    private static class GeneratedSource {
        final File file;
        final Map<Integer, Integer> lineMapping;

        GeneratedSource(File file, Map<Integer, Integer> lineMapping) {
            this.file = file;
            this.lineMapping = lineMapping;
        }
    }

    private static GeneratedSource writeSource(File file, String className, String userCode) throws IOException {
        // Split the original user code into lines while remembering each line's
        // original 1-based line number. Imports are kept separate because they
        // are moved to the top of the generated file.
        String[] rawLines = userCode.split("\n", -1);
        List<String> userImportLines = new ArrayList<>();
        List<Integer> userImportLineNumbers = new ArrayList<>();
        List<String> bodyLines = new ArrayList<>();
        List<Integer> bodyLineNumbers = new ArrayList<>();
        Pattern importPattern = Pattern.compile("^[ \t]*import[ \t]+([^;]+);");

        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            Matcher matcher = importPattern.matcher(line);
            if (matcher.find()) {
                String imported = matcher.group(1).trim();
                if (isAllowedImport(imported)) {
                    userImportLines.add("import " + imported + ";");
                    userImportLineNumbers.add(i + 1);
                }
            } else {
                bodyLines.add(line);
                bodyLineNumbers.add(i + 1);
            }
        }

        // Reconstruct the code body (without imports) exactly as the user wrote it.
        StringBuilder bodyBuilder = new StringBuilder();
        for (int i = 0; i < bodyLines.size(); i++) {
            if (i > 0)
                bodyBuilder.append('\n');
            bodyBuilder.append(bodyLines.get(i));
        }
        String body = bodyBuilder.toString();

        // Users may declare helper fields/methods before the Effect expression.
        // Split the body at the first top-level "new Effect(" so fields/methods go
        // into the class body while the Effect itself becomes the instance initializer.
        int effectStart = findTopLevelNewEffect(body);
        String userFields = "";
        String effectExpr = body;
        int effectExprFirstUserLine = bodyLineNumbers.isEmpty() ? 1 : bodyLineNumbers.get(0);
        if (effectStart >= 0) {
            int bodyLineIndex = 0;
            int charsSoFar = 0;
            while (bodyLineIndex < bodyLines.size()
                    && charsSoFar + bodyLines.get(bodyLineIndex).length() < effectStart) {
                charsSoFar += bodyLines.get(bodyLineIndex).length() + 1; // +1 for '\n'
                bodyLineIndex++;
            }
            if (bodyLineIndex < bodyLineNumbers.size()) {
                effectExprFirstUserLine = bodyLineNumbers.get(bodyLineIndex);
            }
            userFields = body.substring(0, effectStart).trim();
            effectExpr = body.substring(effectStart).trim();
        }

        // The Effect expression is inserted as a field initializer, so it must end with
        // a semicolon.
        if (!effectExpr.endsWith(";")) {
            effectExpr = effectExpr + ";";
        }

        // Build the generated source line by line so we can record the mapping.
        Map<Integer, Integer> mapping = new HashMap<>();
        List<String> generatedLines = new ArrayList<>();

        for (int i = 0; i < userImportLines.size(); i++) {
            generatedLines.add(userImportLines.get(i));
            mapping.put(generatedLines.size(), userImportLineNumbers.get(i));
        }
        generatedLines.add("");

        generatedLines.add("public class " + className + " {");

        if (!userFields.isEmpty()) {
            String[] fieldLines = userFields.split("\n");
            int fieldStartUserLine = bodyLineNumbers.isEmpty() ? 1 : bodyLineNumbers.get(0);
            for (int i = 0; i < fieldLines.length; i++) {
                String fl = fieldLines[i];
                if (fl.trim().isEmpty()) {
                    generatedLines.add("");
                } else {
                    generatedLines.add("    " + fl);
                }
                mapping.put(generatedLines.size(), fieldStartUserLine + i);
            }
            generatedLines.add("");
        }

        String[] effectLines = effectExpr.split("\n");
        for (int i = 0; i < effectLines.length; i++) {
            String el = effectLines[i];
            if (i == 0) {
                generatedLines.add("    public static final Effect instance = " + el);
            } else {
                generatedLines.add(el);
            }
            mapping.put(generatedLines.size(), effectExprFirstUserLine + i);
        }

        generatedLines.add("}");
        generatedLines.add("");

        StringBuilder sourceBuilder = new StringBuilder();
        for (String line : generatedLines) {
            sourceBuilder.append(line).append('\n');
        }

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(sourceBuilder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        return new GeneratedSource(file, mapping);
    }

    /**
     * Checks every string texture reference in the user code (Draw.rect,
     * atlas.find,
     * Core.atlas.find, region, texture, etc.) against the sprites bundled in the
     * APK
     * and the custom sprites folder. Missing textures are reported as a compilation
     * error instead of crashing at render time.
     */
    private static void validateTextures(String userCode, Context context) throws Exception {
        Pattern pattern = Pattern.compile(
                "(Draw\\.rect|Core\\.atlas\\.find|atlas\\.find|region|texture|Drawf\\.rect)\\s*\\(\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(userCode);
        Set<String> textureNames = new HashSet<>();
        while (matcher.find()) {
            textureNames.add(matcher.group(2));
        }
        if (textureNames.isEmpty()) {
            return;
        }

        Set<String> available = new HashSet<>();
        // Arc primitives added by the preview atlas.
        available.add("white");
        available.add("circle");

        // Built-in Mindustry effect sprites shipped in assets.
        try {
            String[] assetNames = context.getAssets().list("sprites/effects");
            if (assetNames != null) {
                for (String name : assetNames) {
                    if (name.toLowerCase().endsWith(".png")) {
                        available.add(name.substring(0, name.length() - 4));
                    }
                }
            }
        } catch (IOException ignored) {
        }

        // User-provided custom sprites in external storage.
        File customDir = new File(context.getExternalFilesDir(null), "sprites");
        if (customDir.exists() && customDir.isDirectory()) {
            File[] files = customDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (name.toLowerCase().endsWith(".png")) {
                        available.add(name.substring(0, name.length() - 4));
                    }
                }
            }
        }

        for (String name : textureNames) {
            if (!available.contains(name)) {
                throw new RuntimeException("贴图" + name + "不存在");
            }
        }
    }

    /**
     * Returns the index of the first top-level {@code new Effect(} in {@code code},
     * skipping strings and comments and tracking brace nesting. Returns -1 if no
     * top-level Effect expression is found.
     */
    private static int findTopLevelNewEffect(String code) {
        int i = 0;
        int len = code.length();
        int braceDepth = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        while (i < len - 10) { // "new Effect(" is 11 chars
            char c = code.charAt(i);

            if (inLineComment) {
                if (c == '\n')
                    inLineComment = false;
                i++;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < len && code.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\' && i + 1 < len) {
                    i += 2;
                } else {
                    if (c == stringChar)
                        inString = false;
                    i++;
                }
                continue;
            }

            if (c == '/' && i + 1 < len) {
                char next = code.charAt(i + 1);
                if (next == '/') {
                    inLineComment = true;
                    i += 2;
                    continue;
                } else if (next == '*') {
                    inBlockComment = true;
                    i += 2;
                    continue;
                }
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
                i++;
                continue;
            }
            if (c == '{' || c == '(' || c == '[') {
                braceDepth++;
                i++;
                continue;
            }
            if (c == '}' || c == ')' || c == ']') {
                braceDepth--;
                i++;
                continue;
            }

            if (braceDepth == 0 && code.startsWith("new Effect", i)) {
                int after = i + 10;
                if (after >= len || !Character.isJavaIdentifierPart(code.charAt(after))) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static boolean isAllowedImport(String imported) {
        // Plain static imports are allowed if their target class is in an allowed
        // package.
        String target = imported;
        if (target.startsWith("static ")) {
            target = target.substring("static ".length()).trim();
            // Drop the member name.
            int lastDot = target.lastIndexOf('.');
            if (lastDot > 0) {
                target = target.substring(0, lastDot);
            }
        }
        for (String prefix : ALLOWED_IMPORT_PREFIXES) {
            if (target.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static synchronized File getOrExtractRuntimeLibs(Context context) throws IOException {
        if (cachedRuntimeJar != null && cachedRuntimeJar.exists()) {
            return cachedRuntimeJar;
        }
        File dir = new File(context.getFilesDir(), "effect_compiler");
        dir.mkdirs();
        File out = new File(dir, RUNTIME_LIBS_ASSET);
        extractAsset(context, RUNTIME_LIBS_ASSET, out);
        cachedRuntimeJar = out;
        return out;
    }

    private static synchronized File getOrExtractAndroidJar(Context context) throws IOException {
        if (cachedAndroidJar != null && cachedAndroidJar.exists()) {
            return cachedAndroidJar;
        }
        File dir = new File(context.getFilesDir(), "effect_compiler");
        dir.mkdirs();
        File out = new File(dir, ANDROID_JAR_ASSET);
        extractAsset(context, ANDROID_JAR_ASSET, out);
        cachedAndroidJar = out;
        return out;
    }

    private static synchronized File getOrExtractLambdaStubs(Context context) throws IOException {
        if (cachedLambdaStubsJar != null && cachedLambdaStubsJar.exists()) {
            return cachedLambdaStubsJar;
        }
        File dir = new File(context.getFilesDir(), "effect_compiler");
        dir.mkdirs();
        File out = new File(dir, LAMBDA_STUBS_ASSET);
        extractAsset(context, LAMBDA_STUBS_ASSET, out);
        cachedLambdaStubsJar = out;
        return out;
    }

    private static void extractAsset(Context context, String assetName, File out) throws IOException {
        try (InputStream in = context.getAssets().open(assetName);
                FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
    }

    private static void compileWithEcj(File srcFile, File outputDir, String classpath,
            Map<Integer, Integer> lineMapping) throws Exception {
        StringWriter outWriter = new StringWriter();
        StringWriter errWriter = new StringWriter();
        try {
            Main main = new Main(
                    new PrintWriter(outWriter),
                    new PrintWriter(errWriter),
                    false, null, null);

            String[] args = {
                    "-cp", classpath,
                    "-source", SOURCE_TARGET,
                    "-target", SOURCE_TARGET,
                    "-encoding", "UTF-8",
                    "-d", outputDir.getAbsolutePath(),
                    "-proc:none",
                    srcFile.getAbsolutePath()
            };

            boolean ok = main.compile(args);
            if (!ok) {
                String raw = errWriter.toString();
                if (raw.isEmpty())
                    raw = outWriter.toString();
                String concise = formatEcjErrors(raw, lineMapping);
                Log.e(TAG, "ECJ compile failed: " + raw);
                throw new RuntimeException(concise);
            }
        } catch (RuntimeException re) {
            // Already formatted by us; do not wrap again.
            throw re;
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            // Common on Android when ECJ references a missing JDK class
            // (e.g. javax.lang.model.SourceVersion or java.lang.Runtime.Version).
            Log.e(TAG, "ECJ failed because a JDK class is missing on Android: " + e.getMessage(), e);
            throw new RuntimeException("ECJ 缺少 Android 运行时不存在的 JDK 类: " + e.getMessage() +
                    "。请确认 ECJ 版本与 Android 兼容。", e);
        } catch (Throwable t) {
            Log.e(TAG, "ECJ compile threw", t);
            throw new RuntimeException("ECJ 编译异常: " + t.getMessage(), t);
        }
    }

    /**
     * Parsed ECJ error entry. ECJ reports one block per error, containing the
     * generated line number, the offending source line and a human-readable
     * message.
     */
    private static class EcjError {
        final int generatedLine;
        final String sourceLine;
        final String message;

        EcjError(int generatedLine, String sourceLine, String message) {
            this.generatedLine = generatedLine;
            this.sourceLine = sourceLine;
            this.message = message;
        }

        /**
         * Returns a key that identifies the root cause of this error. Errors that
         * share the same root cause are cascading consequences of a single mistake
         * (for example, every use of an undeclared variable).
         */
        String rootCauseKey() {
            String var = extractUnresolvedName(message, "cannot be resolved to a variable");
            if (var != null)
                return "var:" + var;

            String field = extractUnresolvedName(message, "cannot be resolved to a field");
            if (field != null)
                return "field:" + field;

            String type = extractUnresolvedName(message, "cannot be resolved to a type");
            if (type != null)
                return "type:" + type;

            if (message.contains("Syntax error on token")) {
                return "syntax:" + generatedLine + ":" + message;
            }
            if (message.contains("The method") && message.contains("is undefined")) {
                return "method:" + message;
            }
            return "other:" + generatedLine + ":" + message;
        }
    }

    /**
     * Parses ECJ's multi-line error output, collapses cascading errors that share
     * the same root cause, maps generated line numbers back to the user's original
     * line numbers, and translates common messages into Chinese.
     */
    private static String formatEcjErrors(String rawErrors, Map<Integer, Integer> lineMapping) {
        List<EcjError> errors = parseEcjErrors(rawErrors);

        if (errors.isEmpty()) {
            // Fallback when the output does not match the expected format.
            return "编译失败:\n" + cleanupRawErrors(rawErrors);
        }

        // Keep only the first occurrence of each root cause. This removes the
        // torrent of "e cannot be resolved" / "fin cannot be resolved" etc.
        // that follow from a few real mistakes.
        Set<String> seenRoots = new LinkedHashSet<>();
        List<EcjError> filtered = new ArrayList<>();
        for (EcjError e : errors) {
            String root = e.rootCauseKey();
            if (seenRoots.add(root)) {
                filtered.add(e);
            }
        }

        int limit = Math.min(filtered.size(), 5);
        StringBuilder sb = new StringBuilder("编译失败:\n");
        for (int i = 0; i < limit; i++) {
            EcjError e = filtered.get(i);
            int userLine = lineMapping.getOrDefault(e.generatedLine, e.generatedLine);
            sb.append("第 ").append(userLine).append(" 行: ")
                    .append(translateEcjMessage(e.message, e.sourceLine)).append('\n');
        }
        if (filtered.size() > limit) {
            sb.append("... 还有 ").append(filtered.size() - limit).append(" 个相关错误未显示");
        }
        return sb.toString();
    }

    /**
     * Line-oriented ECJ error parser. ECJ's exact formatting depends on the
     * output writer and version, so we scan for "(at line N)" markers and
     * read the surrounding source line and message instead of relying on a
     * single strict regex.
     */
    private static List<EcjError> parseEcjErrors(String rawErrors) {
        List<EcjError> errors = new ArrayList<>();
        if (rawErrors == null || rawErrors.trim().isEmpty()) {
            return errors;
        }

        String[] lines = rawErrors.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            Matcher lineMatcher = Pattern.compile("\\(at line (\\d+)\\)").matcher(line);
            if (lineMatcher.find()) {
                try {
                    int generatedLine = Integer.parseInt(lineMatcher.group(1));
                    String sourceLine = "";
                    String message = "";
                    int j = i + 1;

                    // Skip blank lines after the header.
                    while (j < lines.length && lines[j].trim().isEmpty())
                        j++;

                    // The next non-meta line is usually the offending source line.
                    if (j < lines.length && !isEcjMetaLine(lines[j])) {
                        sourceLine = lines[j].trim();
                        j++;
                    }

                    // Skip blank lines and caret/marker lines.
                    while (j < lines.length
                            && (lines[j].trim().isEmpty() || lines[j].trim().matches("[\\s\\^]+"))) {
                        j++;
                    }

                    // The following non-meta line is the message.
                    if (j < lines.length && !isEcjMetaLine(lines[j])) {
                        message = lines[j].trim();
                        j++;
                        // Absorb indented continuation lines.
                        while (j < lines.length
                                && (lines[j].startsWith("\t") || lines[j].startsWith("    "))) {
                            message += " " + lines[j].trim();
                            j++;
                        }
                    }

                    errors.add(new EcjError(generatedLine, sourceLine, message));
                    i = j;
                    continue;
                } catch (NumberFormatException ignored) {
                    // Malformed line number; advance normally.
                }
            }
            i++;
        }
        return errors;
    }

    private static boolean isEcjMetaLine(String line) {
        String t = line.trim();
        return t.startsWith("---") || t.startsWith("===")
                || t.contains("ERROR in") || t.contains("WARNING in")
                || t.matches("\\d+ problems?.*") || t.matches("\\d+ errors?.*")
                || t.matches("\\d+ warnings?.*");
    }

    private static String cleanupRawErrors(String rawErrors) {
        String[] lines = rawErrors.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty())
                continue;
            if (t.startsWith("---"))
                continue;
            if (t.matches("\\d+ problems?.*"))
                continue;
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static String translateEcjMessage(String message, String sourceLine) {
        String var = extractUnresolvedName(message, "cannot be resolved to a variable");
        if (var != null) {
            return "变量 \"" + var + "\" 未声明";
        }

        String field = extractUnresolvedName(message, "cannot be resolved to a field");
        if (field != null) {
            return "字段 \"" + field + "\" 未声明";
        }

        String type = extractUnresolvedName(message, "cannot be resolved to a type");
        if (type != null) {
            return "类型 \"" + type + "\" 未找到";
        }

        if (message.contains("Syntax error on token")) {
            // ECJ often reports a syntax error one line after the real mistake,
            // e.g. a missing semicolon on the previous line.
            if (message.contains("expected")) {
                int expectedIdx = message.indexOf("expected");
                String expected = message.substring(expectedIdx);
                if (message.contains("; expected")) {
                    return "语法错误，可能需要在此处或上一行添加分号";
                }
                return "语法错误，缺少 " + expected;
            }
            return "语法错误: " + message;
        }

        if (message.contains("The method") && message.contains("is undefined")) {
            return "调用了未定义的方法: " + message;
        }
        if (message.contains("Type mismatch")) {
            return "类型不匹配: " + message;
        }
        if (message.contains("Duplicate local variable")) {
            return "变量重复定义: " + message;
        }
        if (message.contains("is not applicable for the arguments")) {
            return "方法参数不匹配: " + message;
        }
        return message;
    }

    private static String extractUnresolvedName(String message, String marker) {
        int idx = message.indexOf(marker);
        if (idx > 0) {
            return message.substring(0, idx).trim();
        }
        return null;
    }

    private static void dexWithR8(File classFile, File dexFile, File androidJar, File runtimeJar) throws Exception {
        try {
            D8Command command = D8Command.builder()
                    .addProgramFiles(classFile.toPath())
                    .addLibraryFiles(androidJar.toPath(), runtimeJar.toPath())
                    .setMinApiLevel(21)
                    .setOutput(dexFile.getParentFile().toPath(), OutputMode.DexIndexed)
                    .build();
            D8.run(command);
            // DexClassLoader requires the dex file to be read-only.
            // D8 leaves it writable, so force it read-only before loading.
            if (!dexFile.setWritable(false, false)) {
                Log.w(TAG, "Could not mark dex file read-only via File API, trying chmod");
                try {
                    Runtime.getRuntime().exec(new String[] { "chmod", "444", dexFile.getAbsolutePath() }).waitFor();
                } catch (Throwable ignored) {
                    // ignore: setWritable failure on some devices is non-fatal
                }
            }
            if (!dexFile.canRead()) {
                throw new IOException("D8 did not produce a readable dex file: " + dexFile);
            }
        } catch (Throwable t) {
            Log.e(TAG, "D8 dexing failed", t);
            throw new RuntimeException("D8 转 dex 失败: " + t.getMessage(), t);
        }
    }

}
