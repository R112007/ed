package com.r112007.effecteditor.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.Modifier; // 需要导入 Modifier 类

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 将用户写的完整 Java 类反向解析为编辑器内部格式。
 * 支持：完整类、裸字段声明（自动包装）、匿名类形式、链式调用（.layer() 等）。
 */
public class ClassReverseParser {

    public static class ParseResult {
        public final List<String> imports = new ArrayList<>();
        /** 主 Effect 的初始化表达式（如 new Effect(60f, e -> {...}).layer(...)） */
        public String mainEffectCode = "";
        /** 主 Effect 的 lambda 体内部代码（用于编译） */
        public String mainLambdaBody = "";
        /** 主 Effect 的 duration */
        public String mainDuration = "60f";
        /** 主 Effect 的 size */
        public String mainSize = "0f";
        /** 其他 Effect 变量的完整代码（如 hitSpark = new Effect(...)） */
        public final List<String> otherEffectCodes = new ArrayList<>();
        /** 非 Effect 的自定义字段 */
        public final List<String> extraFields = new ArrayList<>();
        /** 工具方法 */
        public final List<String> methods = new ArrayList<>();
    }

    public static ParseResult parse(String source, String preferredMainField) {
        ParseResult result = new ParseResult();
        JavaParser parser = new JavaParser();
        Optional<CompilationUnit> opt = parser.parse(source).getResult();
        
        if (!opt.isPresent()) {
            // 尝试自动包装裸字段
            String wrapped = wrapInClass(source);
            opt = parser.parse(wrapped).getResult();
            if (!opt.isPresent()) {
                throw new RuntimeException("Java 语法解析失败，请检查代码是否有语法错误");
            }
        }

        CompilationUnit cu = opt.get();

        // --- 修复 2: 正确提取完整的 import 语句 ---
        for (ImportDeclaration imp : cu.getImports()) {
            // 直接使用 toString() 获取完整的 import 语句，例如 "import mindustry.entities.Effect;"
            result.imports.add(imp.toString().trim());
        }

        ClassOrInterfaceDeclaration clazz = cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new RuntimeException("未找到类声明"));

        // 遍历类成员
        for (BodyDeclaration<?> member : clazz.getMembers()) {
            if (member.isFieldDeclaration()) {
                FieldDeclaration field = member.asFieldDeclaration();
                if (!field.isStatic()) continue;

                // 检查该字段声明中是否包含 Effect
                boolean hasEffect = false;
                for (VariableDeclarator var : field.getVariables()) {
                    Optional<Expression> initOpt = var.getInitializer();
                    if (initOpt.isPresent() && isEffectCreation(initOpt.get())) {
                        hasEffect = true;
                        break;
                    }
                }

                if (hasEffect) {
                    // 处理 Effect 字段声明（可能包含多个变量，逗号分隔）
                    processEffectField(field, preferredMainField, result);
                } else {
                    // 非 Effect 字段，保留完整声明（带修饰符和类型）
                    
                    // --- 修复 1: 正确拼接字段修饰符 ---
                    StringBuilder modifiersBuilder = new StringBuilder();
                    for (Modifier modifier : field.getModifiers()) {
                        modifiersBuilder.append(modifier.getKeyword().asString()).append(" ");
                    }
                    String modifiers = modifiersBuilder.toString().trim();
                    
                    String type = field.getElementType().toString();
                    for (VariableDeclarator v : field.getVariables()) {
                        result.extraFields.add(modifiers + " " + type + " " + v.toString() + ";");
                    }
                }
            } else if (member.isMethodDeclaration()) {
                MethodDeclaration method = member.asMethodDeclaration();
                result.methods.add(method.toString());
            }
        }

        if (result.mainEffectCode.isEmpty()) {
            throw new RuntimeException("未找到 public static final Effect 字段。\n" +
                    "请确保代码中包含类似这样的声明：\n" +
                    "public static final Effect xxx = new Effect(..., e -> { ... });");
        }

        return result;
    }

    /**
     * 处理包含 Effect 的 FieldDeclaration（支持多变量逗号分隔）。
     */
    private static void processEffectField(FieldDeclaration field, String preferredMainField, ParseResult result) {
        boolean mainFound = !result.mainEffectCode.isEmpty();

        // --- 修复 1: 正确拼接字段修饰符 ---
        StringBuilder modifiersBuilder = new StringBuilder();
        for (Modifier modifier : field.getModifiers()) {
            modifiersBuilder.append(modifier.getKeyword().asString()).append(" ");
        }
        String modifiers = modifiersBuilder.toString().trim();

        for (VariableDeclarator var : field.getVariables()) {
            Optional<Expression> initOpt = var.getInitializer();
            if (!initOpt.isPresent()) continue;

            Expression init = initOpt.get();
            if (!isEffectCreation(init)) {
                // 同声明中的非 Effect 变量（理论上不应出现，但以防万一）
                String type = field.getElementType().toString();
                result.extraFields.add(modifiers + " " + type + " " + var.toString() + ";");
                continue;
            }

            String varName = var.getNameAsString();
            String initCode = init.toString();

            if (!mainFound && (preferredMainField == null || preferredMainField.equals(varName))) {
                // 主 Effect
                result.mainEffectCode = initCode;
                extractEffectParams(varName, init, result);
                mainFound = true;
            } else if (mainFound && preferredMainField != null && preferredMainField.equals(varName)) {
                // 优先字段，提升为主 Effect，原来的主 Effect 降为其他
                result.otherEffectCodes.add(result.mainEffectCode);
                result.mainEffectCode = initCode;
                extractEffectParams(varName, init, result);
            } else {
                // 其他 Effect
                
                // --- 修复 3: 避免双分号 ---
                String varString = var.toString();
                // 如果变量声明字符串已经以分号结尾，就不要再添加了
                if (!varString.trim().endsWith(";")) {
                    varString = varString + ";";
                }
                result.otherEffectCodes.add(modifiers + " Effect " + varString);
            }
        }
    }

    private static void extractEffectParams(String fieldName, Expression initExpr, ParseResult result) {
        ObjectCreationExpr creation = unwrapEffectCreation(initExpr);
        if (creation == null) return;

        NodeList<Expression> args = creation.getArguments();
        if (args.size() >= 2) {
            result.mainDuration = args.get(0).toString();
            Expression last = args.get(args.size() - 1);
            if (last.isLambdaExpr()) {
                LambdaExpr lambda = last.asLambdaExpr();
                if (lambda.getBody().isBlockStmt()) {
                    BlockStmt block = lambda.getBody().asBlockStmt();
                    String raw = block.toString();
                    int open = raw.indexOf('{');
                    int close = raw.lastIndexOf('}');
                    if (open >= 0 && close > open) {
                        result.mainLambdaBody = raw.substring(open + 1, close).trim();
                    }
                } else {
                    result.mainLambdaBody = lambda.getBody().toString();
                }
                if (args.size() >= 3) {
                    Expression maybeSize = args.get(args.size() - 2);
                    if (!maybeSize.isLambdaExpr()) {
                        result.mainSize = maybeSize.toString();
                    }
                }
            } else if (creation.getAnonymousClassBody().isPresent()) {
                for (BodyDeclaration<?> m : creation.getAnonymousClassBody().get()) {
                    if (m.isMethodDeclaration()) {
                        MethodDeclaration md = m.asMethodDeclaration();
                        if ("render".equals(md.getNameAsString())) {
                            Optional<BlockStmt> bodyOpt = md.getBody();
                            if (bodyOpt.isPresent()) {
                                String raw = bodyOpt.get().toString();
                                int open = raw.indexOf('{');
                                int close = raw.lastIndexOf('}');
                                if (open >= 0 && close > open) {
                                    result.mainLambdaBody = raw.substring(open + 1, close).trim();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static String wrapInClass(String source) {
        return "class __AutoWrap {\n" + source + "\n}";
    }

    private static boolean isEffectCreation(Expression expr) {
        return unwrapEffectCreation(expr) != null;
    }

    private static ObjectCreationExpr unwrapEffectCreation(Expression expr) {
        if (expr.isObjectCreationExpr()) {
            ObjectCreationExpr creation = expr.asObjectCreationExpr();
            if ("Effect".equals(creation.getType().getNameAsString())) {
                return creation;
            }
        }
        if (expr.isMethodCallExpr()) {
            Optional<Expression> scopeOpt = expr.asMethodCallExpr().getScope();
            if (scopeOpt.isPresent()) {
                return unwrapEffectCreation(scopeOpt.get());
            }
        }
        if (expr.isFieldAccessExpr()) {
            Expression scope = expr.asFieldAccessExpr().getScope();
            if (scope != null) {
                return unwrapEffectCreation(scope);
            }
        }
        return null;
    }
}