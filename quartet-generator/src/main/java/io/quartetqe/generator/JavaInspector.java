package io.quartetqe.generator;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Reads tests through the Java compiler's own syntax tree, so checks see code rather than text. */
public final class JavaInspector {
    public record ActCall(String kind, String instruction, String why, List<String> on, long line) {}

    public record Assertion(String text, String matcher, List<String> literals, long line) {}

    public record TestMethod(String name, List<String> criteria, boolean skeleton, boolean disabled, boolean hasTry,
                             boolean sleeps, boolean assumes, List<ActCall> acts, List<Assertion> assertions, long line,
                             long lastActPosition, long lastAssertPosition) {}

    public record TestFile(String path, String className, String packageName, boolean hasStory, String story,
                           boolean hasExtension, boolean ordered, List<String> mutableStatics, List<TestMethod> tests, List<ActCall> helperActs) {}

    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private JavaInspector() {}

    private static final class Source extends SimpleJavaFileObject {
        private final String code;

        Source(String name, String code) {
            super(URI.create("string:///" + name.replace('\\', '/')), JavaFileObject.Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    /** Compiles the files against the framework. Returns compiler errors, empty when clean. */
    public static List<String> compile(Map<String, String> sources) {
        try {
            Path out = Files.createTempDirectory("agent-compile");
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            List<Source> units = sources.entrySet().stream().map(e -> new Source(e.getKey(), e.getValue())).toList();
            compiler.getTask(null, null, diagnostics,
                    List.of("-proc:none", "-d", out.toString(), "-cp", System.getProperty("java.class.path")), null, units).call();
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    String name = d.getSource() == null ? "unknown" : d.getSource().getName();
                    errors.add(name + ":" + d.getLineNumber() + ": " + d.getMessage(null));
                }
            }
            return errors;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TestFile inspect(String path, String source) {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(new Source(path, source)));
            CompilationUnitTree unit = task.parse().iterator().next();
            return readUnit(path, unit, Trees.instance(task).getSourcePositions());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TestFile readUnit(String path, CompilationUnitTree unit, SourcePositions positions) {
        String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        ClassTree type = unit.getTypeDecls().stream().filter(ClassTree.class::isInstance).map(ClassTree.class::cast).findFirst().orElse(null);
        if (type == null) return new TestFile(path, "", packageName, false, null, false, false, List.of(), List.of(), List.of());

        boolean hasStory = false;
        boolean hasExtension = false;
        boolean ordered = false;
        boolean classDisabled = false;
        String story = null;
        for (AnnotationTree annotation : type.getModifiers().getAnnotations()) {
            String name = simpleName(annotation);
            if (name.equals("Story")) {
                hasStory = true;
                story = firstString(annotation.toString());
            }
            if (name.equals("ExtendWith") && annotation.toString().contains("AgentExtension")) hasExtension = true;
            if (name.equals("TestMethodOrder")) ordered = true;
            if (disables(name)) classDisabled = true;
        }

        List<String> mutableStatics = new ArrayList<>();
        List<TestMethod> tests = new ArrayList<>();
        List<ActCall> helperActs = new ArrayList<>();
        LineMap lines = unit.getLineMap();
        for (Tree member : type.getMembers()) {
            if (member instanceof VariableTree field && field.getModifiers().getFlags().contains(Modifier.STATIC)
                    && !field.getModifiers().getFlags().contains(Modifier.FINAL)) {
                mutableStatics.add(field.getName().toString());
            }
            if (member instanceof MethodTree method) {
                TestMethod read = readMethod(method, unit, positions, lines);
                if (isTest(method)) tests.add(classDisabled ? disabled(read) : read);
                else helperActs.addAll(read.acts());
            }
        }
        return new TestFile(path, type.getSimpleName().toString(), packageName, hasStory, story, hasExtension, ordered, mutableStatics, tests, helperActs);
    }

    /** Annotations can be written with their full package name, which must not bypass any check. */
    private static String simpleName(AnnotationTree annotation) {
        String name = annotation.getAnnotationType().toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static boolean disables(String annotation) {
        return annotation.startsWith("Disabled") || annotation.startsWith("Enabled");
    }

    private static TestMethod disabled(TestMethod t) {
        return new TestMethod(t.name(), t.criteria(), t.skeleton(), true, t.hasTry(), t.sleeps(), t.assumes(), t.acts(), t.assertions(), t.line(),
                t.lastActPosition(), t.lastAssertPosition());
    }

    private static boolean isTest(MethodTree method) {
        return method.getModifiers().getAnnotations().stream().anyMatch(a -> simpleName(a).matches("Test|ParameterizedTest|OnEverySurface"));
    }

    private static TestMethod readMethod(MethodTree method, CompilationUnitTree unit, SourcePositions positions, LineMap lines) {
        List<String> criteria = new ArrayList<>();
        boolean skeleton = false;
        boolean disabled = false;
        for (AnnotationTree annotation : method.getModifiers().getAnnotations()) {
            String name = simpleName(annotation);
            if (name.equals("AcceptanceCriteria")) criteria.addAll(strings(annotation.toString()));
            if (name.equals("WalkingSkeleton")) skeleton = true;
            if (disables(name)) disabled = true;
        }

        List<ActCall> acts = new ArrayList<>();
        List<Assertion> assertions = new ArrayList<>();
        boolean[] flags = new boolean[3];
        long[] last = {-1, -1};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitTry(TryTree node, Void unused) {
                flags[0] = true;
                return super.visitTry(node, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                String name = methodName(node);
                long start = positions.getStartPosition(unit, node);
                long end = positions.getEndPosition(unit, node);
                if (name.equals("sleep") && node.getMethodSelect().toString().contains("Thread")) flags[1] = true;
                if (name.startsWith("assume")) flags[2] = true;
                if ((name.equals("act") || name.equals("verify")) && !node.getArguments().isEmpty() && node.getMethodSelect() instanceof MemberSelectTree) {
                    acts.add(readAct(name, node, lines.getLineNumber(start)));
                    last[0] = Math.max(last[0], end);
                    if (name.equals("verify")) last[1] = Math.max(last[1], end);
                }
                if (isAssertion(node, name)) {
                    assertions.add(new Assertion(node.toString(), name, matcherLiterals(node), lines.getLineNumber(start)));
                    last[1] = Math.max(last[1], end);
                }
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(method.getBody(), null);

        return new TestMethod(method.getName().toString(), criteria, skeleton, disabled, flags[0], flags[1], flags[2], acts, assertions,
                lines.getLineNumber(positions.getStartPosition(unit, method)), last[0], last[1]);
    }

    private static ActCall readAct(String kind, MethodInvocationTree node, long line) {
        String instruction = literalOrText(node.getArguments().get(0));
        String why = null;
        List<String> on = new ArrayList<>();
        for (ExpressionTree argument : node.getArguments()) {
            if (argument instanceof MethodInvocationTree option) {
                String name = methodName(option);
                if (name.equals("why") && !option.getArguments().isEmpty()) why = literalOrText(option.getArguments().get(0));
                if (name.equals("on")) option.getArguments().forEach(target -> on.add(literalOrText(target)));
            }
        }
        return new ActCall(kind, instruction, why, on, line);
    }

    private static boolean isAssertion(MethodInvocationTree node, String name) {
        if (name.startsWith("assert") && !name.equals("assertThat")) return true;
        if (name.equals("assertThat") || !(node.getMethodSelect() instanceof MemberSelectTree)) return false;
        ExpressionTree current = ((MemberSelectTree) node.getMethodSelect()).getExpression();
        while (current instanceof MethodInvocationTree invocation) {
            if (methodName(invocation).equals("assertThat")) return true;
            if (!(invocation.getMethodSelect() instanceof MemberSelectTree select)) return false;
            current = select.getExpression();
        }
        return false;
    }

    private static List<String> matcherLiterals(MethodInvocationTree node) {
        List<String> literals = new ArrayList<>();
        for (ExpressionTree argument : node.getArguments()) {
            if (argument instanceof LiteralTree literal) literals.add(String.valueOf(literal.getValue()));
        }
        return literals;
    }

    private static String methodName(MethodInvocationTree node) {
        ExpressionTree select = node.getMethodSelect();
        if (select instanceof MemberSelectTree member) return member.getIdentifier().toString();
        if (select instanceof IdentifierTree identifier) return identifier.getName().toString();
        return "";
    }

    private static String literalOrText(ExpressionTree tree) {
        return tree instanceof LiteralTree literal ? String.valueOf(literal.getValue()) : tree.toString();
    }

    private static String firstString(String text) {
        Matcher matcher = STRING.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static List<String> strings(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = STRING.matcher(text);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }
}
