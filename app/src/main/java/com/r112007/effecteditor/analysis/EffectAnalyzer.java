package com.r112007.effecteditor.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deep semantic analyzer using JavaParser (the "inner" layer).
 * Triggered only by explicit user actions to avoid blocking the UI on every keystroke.
 * Validates that the snippet is a syntactically valid {@code new Effect(...)} expression.
 */
public class EffectAnalyzer {

    public static class Result {
        public final boolean success;
        public final String message;

        public Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private static final Pattern IMPORT_PATTERN = Pattern.compile("^[ \\t]*import[ \\t]+([^;]+);", Pattern.MULTILINE);

    public static Result analyze(String userCode) {
        String wrapped = wrapSnippet(userCode);
        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> result = parser.parse(wrapped);
        if (!result.isSuccessful() || !result.getResult().isPresent()) {
            return new Result(false, "语法错误: " + result.getProblems().get(0).getMessage());
        }

        CompilationUnit cu = result.getResult().get();
        ObjectCreationExpr effectExpr = cu.findFirst(ObjectCreationExpr.class, expr -> {
            if (!expr.getScope().isPresent()) {
                String name = expr.getType().asString();
                return name.equals("Effect") || name.endsWith("Effect");
            }
            return false;
        }).orElse(null);

        if (effectExpr == null) {
            return new Result(false, "未找到 new Effect(...) 表达式");
        }

        int argCount = effectExpr.getArguments().size();
        if (argCount < 2 || argCount > 3) {
            return new Result(false, "Effect 构造函数需要 2 或 3 个参数 (lifetime, [clipsize], renderer)");
        }

        // Validate the last argument is a lambda.
        Expression lastArg = effectExpr.getArguments().get(argCount - 1);
        if (!lastArg.isLambdaExpr()) {
            return new Result(false, "最后一个参数必须是 lambda 渲染器 e -> { ... }");
        }

        return new Result(true, "发现 Effect 定义，参数数量: " + argCount);
    }

    private static String wrapSnippet(String userCode) {
        String code = userCode.trim();

        // Extract imports so they can be placed outside the wrapping class.
        List<String> imports = new ArrayList<>();
        Matcher matcher = IMPORT_PATTERN.matcher(code);
        while (matcher.find()) {
            imports.add(matcher.group(0));
        }
        code = matcher.replaceAll("").trim();

        // Users may declare helper fields/methods before the Effect expression.
        // Split the body at the first top-level "new Effect(" so fields/methods go
        // into the class body while the Effect itself becomes the initializer.
        int effectStart = findTopLevelNewEffect(code);
        String userFields = "";
        String effectExpr = code;
        if (effectStart >= 0) {
            userFields = code.substring(0, effectStart).trim();
            effectExpr = code.substring(effectStart).trim();
        }

        if (!effectExpr.endsWith(";")) {
            effectExpr = effectExpr + ";";
        }

        StringBuilder sb = new StringBuilder();
        for (String imp : imports) {
            sb.append(imp).append("\n");
        }
        sb.append("public class __EffectSnippet {\n");
        if (!userFields.isEmpty()) {
            String[] lines = userFields.split("\n");
            for (String line : lines) {
                if (line.trim().isEmpty()) {
                    sb.append("\n");
                } else {
                    sb.append("    ").append(line).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("    static final Object effect = ").append(effectExpr).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Returns the index of the first top-level {@code new Effect} in {@code code},
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

        while (i < len - 9) { // "new Effect" is 10 chars
            char c = code.charAt(i);

            if (inLineComment) {
                if (c == '\n') inLineComment = false;
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
                    if (c == stringChar) inString = false;
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
}
