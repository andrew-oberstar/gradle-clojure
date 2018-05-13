package gradle_clojure.plugin.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;

import gradle_clojure.plugin.tasks.ClojureExecSpec;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.LogLevel;
import org.gradle.workers.IsolationMode;
import org.gradle.workers.WorkerExecutor;
import us.bpsm.edn.printer.Printers;

public final class ClojureExecutor {
  private static final String SHIMDANDY_VERSION = "1.2.1";
  private static final String NREPL_VERSION = "0.2.12";
  private static final String GRADLE_CLOJURE_VERSION = getVersion();

  private final Project project;
  private final WorkerExecutor workerExecutor;

  public ClojureExecutor(Project project, WorkerExecutor workerExecutor) {
    this.project = project;
    this.workerExecutor = workerExecutor;
  }

  public void exec(Action<ClojureExecSpec> action) {
    ClojureExecSpec cljSpec = new ClojureExecSpec();
    action.execute(cljSpec);
    exec(cljSpec);
  }

  public void exec(ClojureExecSpec cljSpec) {
    FileCollection fullClasspath = cljSpec.getClasspath().plus(resolve(tools(), nrepl()));
    project.javaexec(spec -> {
      spec.setMain("clojure.main");
      spec.args("-m", cljSpec.getMain());

      String ednArgs = Printers.printString(Printers.prettyPrinterProtocol(), Arrays.asList(cljSpec.getArgs()));
      ByteArrayInputStream input = new ByteArrayInputStream(ednArgs.getBytes(StandardCharsets.UTF_8));
      spec.setStandardInput(input);

      spec.setClasspath(fullClasspath);
      cljSpec.getConfigureFork().forEach(forkAction -> forkAction.execute(spec));
    });
  }

  public void submit(Action<ClojureExecSpec> action) {
    ClojureExecSpec cljSpec = new ClojureExecSpec();
    action.execute(cljSpec);

    FileCollection additionalClasspath = resolve(tools(), nrepl(), shimImpl());
    FileCollection realClasspath = cljSpec.getClasspath().plus(additionalClasspath);

    workerExecutor.submit(ClojureWorker.class, worker -> {
      worker.setIsolationMode(IsolationMode.PROCESS);
      worker.params(cljSpec.getMain(), cljSpec.getArgs(), realClasspath.getFiles());
      cljSpec.getConfigureFork().forEach(worker::forkOptions);
      worker.forkOptions(fork -> {
        fork.systemProperty("gradle-clojure.tools.logger.level", getLogLevel());
      });
      worker.classpath(resolve(shimApi()));
    });
  }

  public void await() {
    workerExecutor.await();
  }

  public FileCollection resolve(Dependency... deps) {
    return project.getConfigurations().detachedConfiguration(deps).setTransitive(false);
  }

  public Dependency tools() {
    return project.getDependencies().create("io.github.gradle-clojure:gradle-clojure-tools:" + GRADLE_CLOJURE_VERSION);
  }

  public Dependency nrepl() {
    return project.getDependencies().create("org.clojure:tools.nrepl:" + NREPL_VERSION);
  }

  public Dependency shimApi() {
    return project.getDependencies().create("org.projectodd.shimdandy:shimdandy-api:" + SHIMDANDY_VERSION);
  }

  public Dependency shimImpl() {
    return project.getDependencies().create("org.projectodd.shimdandy:shimdandy-impl:" + SHIMDANDY_VERSION);
  }

  private static String getVersion() {
    try (InputStream stream = ClojureExecutor.class.getResourceAsStream("/gradle-clojure.properties")) {
      Properties props = new Properties();
      props.load(stream);
      return props.getProperty("version");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String getLogLevel() {
    Supplier<String> gradleLevel = () -> Stream.of(LogLevel.DEBUG, LogLevel.INFO, LogLevel.LIFECYCLE, LogLevel.WARN, LogLevel.QUIET, LogLevel.ERROR)
        .filter(project.getLogger()::isEnabled)
        .map(LogLevel::toString)
        .map(String::toLowerCase)
        .findFirst()
        .orElse("info");

    // allow level to come from either a project property or whatever level Gradle is set to
    return Optional.ofNullable(project.findProperty("gradle-clojure.tools.logger.level"))
        .map(Object::toString)
        .orElseGet(gradleLevel);
  }
}