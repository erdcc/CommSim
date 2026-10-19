package comm.scenario;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import groovy.transform.ThreadInterrupt;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Groovy betiklerini derleyip calistirir. Yalnizca .groovy senaryolarinda yuklenir. */
final class GroovySupport {
    private GroovySupport() { }

    /** Derlenmis betik. */
    static final class Compiled {
        private final Class<?> type;
        private final String fileName;

        Compiled(Class<?> type, String fileName) {
            this.type = type;
            this.fileName = fileName;
        }

        /** Betigi calistirir; hatalari satir numarasiyla ScenarioException'a cevirir. */
        void run(ScenarioRunner.Api api, BooleanSupplier stopped) throws InterruptedException, ScenarioException {
            Script script;
            try {
                script = (Script) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new ScenarioException("betik oluşturulamadı: " + e);
            }
            Binding b = new Binding();
            b.setVariable(SimScript.API, api);
            script.setBinding(b);
            try {
                script.run();
            } catch (RuntimeException e) {
                if (isFlow(e)) throw e;
                if (stopped.getAsBoolean() || e.getCause() instanceof InterruptedException) throw new InterruptedException();
                throw new ScenarioException(where(e) + describe(e));
            } catch (Error e) {
                throw e;
            } catch (Throwable e) { // Groovy denetimli istisnalari bildirmeden firlatabilir
                if (e instanceof InterruptedException || stopped.getAsBoolean()) throw new InterruptedException();
                if (e instanceof ScenarioException) throw (ScenarioException) e;
                throw new ScenarioException(where(e) + describe(e));
            }
        }

        private static boolean isFlow(Throwable e) {
            for (Class<?> c = e.getClass(); c != null; c = c.getSuperclass()) {
                if (c.getName().equals(ScenarioRunner.class.getName() + "$Flow")) return true;
            }
            return false;
        }

        /** Betikteki satir: yigindaki ilk betik cercevesi. */
        private String where(Throwable e) {
            for (StackTraceElement el : e.getStackTrace()) {
                if (fileName.equals(el.getFileName()) && el.getLineNumber() > 0) return fileName + " satır " + el.getLineNumber() + ": ";
            }
            return fileName + ": ";
        }
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (e instanceof groovy.lang.MissingMethodException) {
            groovy.lang.MissingMethodException mm = (groovy.lang.MissingMethodException) e;
            return "bilinmeyen yöntem ya da yanlış argümanlar: " + mm.getMethod() + "(…)";
        }
        if (e instanceof groovy.lang.MissingPropertyException) {
            return "tanımsız değişken: " + ((groovy.lang.MissingPropertyException) e).getProperty();
        }
        return m == null ? e.toString() : m;
    }

    /** @throws ScenarioException derleme hatasi (satir:sutun ile) */
    static Compiled compile(String code, String fileName, Path baseDir) throws ScenarioException {
        String name = fileName == null || fileName.trim().isEmpty() ? "senaryo.groovy" : fileName;
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.setScriptBaseClass(SimScript.class.getName());
        cc.setSourceEncoding("UTF-8");
        // Durdur dugmesi donguleri de kesebilsin
        cc.addCompilationCustomizers(new ASTTransformationCustomizer(ThreadInterrupt.class));
        GroovyClassLoader loader = new GroovyClassLoader(GroovySupport.class.getClassLoader(), cc);
        if (baseDir != null) loader.addClasspath(baseDir.toString());
        try {
            return new Compiled(loader.parseClass(code, name), name);
        } catch (MultipleCompilationErrorsException e) {
            StringBuilder sb = new StringBuilder();
            for (Object o : e.getErrorCollector().getErrors()) {
                if (sb.length() > 0) sb.append("; ");
                if (o instanceof SyntaxErrorMessage) {
                    org.codehaus.groovy.syntax.SyntaxException se = ((SyntaxErrorMessage) o).getCause();
                    sb.append(name).append(" satır ").append(se.getStartLine()).append(':').append(se.getStartColumn())
                            .append(": ").append(se.getOriginalMessage());
                } else if (o instanceof Message) {
                    java.io.StringWriter w = new java.io.StringWriter();
                    ((Message) o).write(new java.io.PrintWriter(w));
                    sb.append(w.toString().trim());
                }
            }
            throw new ScenarioException(sb.length() == 0 ? e.getMessage() : sb.toString());
        } catch (CompilationFailedException e) {
            throw new ScenarioException(e.getMessage());
        }
    }
}
