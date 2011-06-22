// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.Java;
import bake.tool.BakeError;
import bake.tool.Files;
import bake.tool.Handler;
import bake.tool.Log;
import bake.tool.Module;
import bake.tool.Repository;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;

import javax.inject.Inject;
import javax.inject.Provider;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static bake.tool.java.ExternalDependency.isExternal;
import static bake.tool.java.WalkStrategy.ALL_TESTS;
import static bake.tool.java.WalkStrategy.CURRENT_TESTS;
import static bake.tool.java.WalkStrategy.NO_TESTS;

/**
 * Bakes Java libraries.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class JavaHandler implements Handler<Java> {

  final Java java;
  final Repository repository;
  final Module module;
  final ExternalDependencies externalDependencies;
  final Intellij intellij;
  final Provider<IncrementalCompiler> compilerProvider;
  final ExecutableJar executableJar = new FatJar(this);

  @Inject JavaHandler(Java java, Repository repository, Module module,
      Provider<IncrementalCompiler> compilerProvider, ExternalDependencies externalDependencies,
      Intellij intellij) {
    this.java = java;
    this.repository = repository;
    this.module = module;
    this.intellij = intellij;
    this.compilerProvider = compilerProvider;
    this.externalDependencies = externalDependencies;

    externalDependencies.setHandler(this);
    intellij.setHandler(this);
  }

  public Java annotation() {
    return java;
  }

  /** Returns the destination directory for classes. */
  private File classesDirectory() throws IOException {
    return module.outputDirectory("classes");
  }

  /** Returns the destination directory for test classes. */
  private File testClassesDirectory() throws IOException {
    return module.outputDirectory("test-classes");
  }

  public void bake() throws IOException, BakeError {
    // Resolve external dependencies.
    walk(new JavaTask() {
      @Override public void execute(JavaHandler handler) throws BakeError, IOException {
        handler.externalDependencies.resolve();
      }
    }, ALL_TESTS);

    intellij.updateAll();

    walk(new JavaTask() {
      @Override public void execute(JavaHandler handler) throws BakeError, IOException {
        handler.compile();
      }
    }, ALL_TESTS);

    if (!java.mainClass().equals("")) executableJar.bake();

    walk(new JavaTask() {
      @Override public void execute(JavaHandler handler) throws BakeError, IOException {
        handler.runTests();
      }
    }, ALL_TESTS);
  }

  /**
   * Walks the module tree from bottom to top. Executes the given task against each module this
   * module depends on and then against this module.
   */
  public void walk(JavaTask task, WalkStrategy strategy) throws BakeError, IOException {
    walk(Maps.<JavaHandler, TaskState>newHashMap(), task, strategy);
  }

  /** State of a task for a given module. */
  private enum TaskState { RUNNING, DONE }

  /**
   * Walks the module tree from bottom to top. Executes the given task against each module this
   * module depends on and then against this module. Uses states to detect circular dependencies
   * and avoid duplication.
   */
  private void walk(Map<JavaHandler, TaskState> states, JavaTask task,
      WalkStrategy strategy) throws BakeError, IOException {
    TaskState taskState = states.get(this);
    if (taskState == TaskState.DONE) {
      Log.v("Already executed %s for %s.", task, module.name());
      return;
    }
    if (taskState == TaskState.RUNNING) {
      // TODO: Output path.
      throw new BakeError("Circular dependency in " + module.name() + ".");
    }
    states.put(this, TaskState.RUNNING);

    // Execute against dependencies first.
    for (Module dependency : directModules(strategy != NO_TESTS)) {
      dependency.javaHandler().walk(states, task, strategy == ALL_TESTS ? ALL_TESTS : NO_TESTS);
    }

    // Execute against this module.
    task.execute(this);

    states.put(this, TaskState.DONE);
  }

  /** Returns the set of direct dependencies for this module. */
  public Collection<Module> directModules(boolean includeTests) throws BakeError, IOException {
    List<Module> modules = Lists.newArrayList();
    Set<String> dependencies = includeTests ? allDependencies() : mainDependencies();
    for (String dependency : dependencies) {
      if (!isExternal(dependency)) modules.add(repository.moduleByName(dependency));
    }
    return modules;
  }

  private Set<String> mainDependencies;

  /**
   * Returns the main direct dependencies, including those exported by other modules.
   */
  public Set<String> mainDependencies() throws BakeError, IOException {
    if (mainDependencies == null) {
      mainDependencies = Collections.unmodifiableSet(mergeExports(java.dependencies()));
    }
    return mainDependencies;
  }

  private Set<String> testDependencies;

  /**
   * Returns all direct test dependencies, including those exported by other modules. Filters
   * out dependencies already included in mainDependencies().
   */
  public Set<String> testDependencies() throws BakeError, IOException {
    if (testDependencies == null) {
      testDependencies = mergeExports(java.testDependencies());
      testDependencies.removeAll(mainDependencies());
      testDependencies = Collections.unmodifiableSet(testDependencies());
    }
    return testDependencies;
  }

  private Set<String> allDependencies;

  /**
   * Returns all direct dependencies, including those exported by other modules.
   */
  public Set<String> allDependencies() throws BakeError, IOException {
    if (allDependencies == null) {
      allDependencies = mergeExports(java.testDependencies());
      allDependencies.addAll(mainDependencies());
      allDependencies = Collections.unmodifiableSet(allDependencies());
    }
    return allDependencies;
  }

  private Set<String> mergeExports(String[] dependencies) throws BakeError, IOException {
    Set<String> all = Sets.newLinkedHashSet();
    for (String dependency : dependencies) {
      all.add(dependency);
      if (!isExternal(dependency)) {
        // Add exported dependencies, too.
        Module other = repository.moduleByName(dependency);
        all.addAll(Arrays.asList(other.javaHandler().annotation().exports()));
      }
    }
    return all;
  }

  /** Gathers jar files needed to run this module. Includes tests. */
  private List<File> allJarsForTests() throws BakeError, IOException {
    // TODO: Exclude jar files from our dependencies' tests.
    final List<File> jarFiles = Lists.newArrayList();
    walk(new JavaTask() {
      @Override public void execute(JavaHandler handler) throws BakeError, IOException {
        jarFiles.add(handler.classesJar());
        jarFiles.addAll(handler.jars());
      }
    }, CURRENT_TESTS);
    addExternalJarsTo(jarFiles);
    return jarFiles;
  }

  /** Adds all external jars to the given list. */
  private void addExternalJarsTo(List<File> jars) {
    for (ExternalArtifact externalArtifact : externalDependencies.all().values()) {
      if (externalArtifact.id.type == ExternalArtifact.Type.JAR) jars.add(externalArtifact.file);
    }
  }

  /** Returns this module's first order external dependencies. */
  Iterable<ExternalDependency> externalDependencies() throws BakeError, IOException {
    return externalDependencies(mainDependencies());
  }

  /** Returns this module's first order external test dependencies. */
  Iterable<ExternalDependency> externalTestDependencies() throws BakeError, IOException {
    return externalDependencies(testDependencies());
  }

  private Iterable<ExternalDependency> externalDependencies(Set<String> dependencies)
      throws BakeError {
    List<ExternalDependency> externalDependencies = Lists.newArrayList();
    for (String dependency : dependencies) {
      if (isExternal(dependency)) externalDependencies.add(ExternalDependency.parse(dependency));
    }
    return externalDependencies;
  }

  // Compile:

  /**
   * Compiles this module and calls {@link #jarClasses()}. Only called once.
   */
  void compile() throws BakeError, IOException {
    if (hasSourceDirectories()) {
      Log.i("Compiling %s...", module.name());

      // Compile main classes.
      IncrementalCompiler mainCompiler = compilerProvider.get();
      appendCompilationDependencies(mainCompiler, mainDependencies());
      for (File jar : jars()) mainCompiler.appendClasspath(jar);
      for (String sourceDirectory : java.source()) {
        mainCompiler.appendSourceDirectory(new File(module.directory(), sourceDirectory));
      }
      // TODO: Add resources, too?
      mainCompiler.destinationDirectory(classesDirectory())
        .database(new File(module.outputDirectory(), "jmake.db"))
        .compile();

      // Compile test classes.
      IncrementalCompiler testCompiler = compilerProvider.get();
      testCompiler.appendClasspath(classesDirectory());
      for (File jar : jars()) testCompiler.appendClasspath(jar);
      appendCompilationDependencies(testCompiler, mainDependencies());
      appendCompilationDependencies(testCompiler, testDependencies());
      for (String sourceDirectory : java.testSource()) {
        testCompiler.appendSourceDirectory(new File(module.directory(), sourceDirectory));
      }
      testCompiler.destinationDirectory(testClassesDirectory())
        .database(new File(module.outputDirectory(), "jmake-tests.db"))
        .compile();
    } else {
      Log.v("%s has no source directories.", module.name());
    }

    jarClasses();
  }

  private void appendCompilationDependencies(IncrementalCompiler compiler,
      Set<String> dependencies) throws BakeError, IOException {
    for (String dependency : dependencies) {
      if (isExternal(dependency)) {
        ExternalDependency parsed = ExternalDependency.parse(dependency);
        ExternalArtifact artifact = externalDependencies.get(parsed.jarId());
        compiler.appendClasspath(artifact.file);
      } else {
        Module otherModule = repository.moduleByName(dependency);
        JavaHandler otherJava = otherModule.javaHandler();
        compiler.appendClasspath(otherJava.classesJar());
        compiler.appendClasspath(otherJava.jars());
      }
    }
  }

  /**
   * Returns true if this Java module has source code directories.
   */
  private boolean hasSourceDirectories() {
    for (String sourceDirectory : meld(java.source(), java.testSource())) {
      if (new File(module.directory(), sourceDirectory).exists()) return true;
    }
    return false;
  }

  /** Combines elements from each array into a single set. */
  private static <T> Set<T> meld(T[]... arrays) {
    Set<T> set = Sets.newLinkedHashSet();
    for (T[] array : arrays) set.addAll(Arrays.asList(array));
    return set;
  }

  // Jar:

  /**
   * Creates a classes.jar containing the classes and resources from this
   * module.
   */
  private void jarClasses() throws IOException {
    // Check class and resource modification times against classes.jar.
    long mostRecent = mostRecentModification(classesDirectory());
    for (String path : java.resources()) {
      File resourcesDirectory = new File(module.directory(), path);
      mostRecent = Math.max(mostRecent,
          mostRecentModification(resourcesDirectory));
    }
    if (mostRecent == -1) {
      Log.v("No classes or resources to jar for %s.", module.name());
      return;
    }

    File classesJar = classesJar();
    if (mostRecent <= classesJar.lastModified()) {
      Log.i("%s is up to date.", repository.relativePath(classesJar));
      return;
    }

    Log.i("Jarring classes and resources for %s...", module.name());
    File temp = new File(classesJar.getPath() + ".temp");
    FileOutputStream fout = new FileOutputStream(temp);
    try {
      ZipOutputStream zout = new ZipOutputStream(
          new BufferedOutputStream(fout));
      Set<String> paths = Sets.newHashSet("/");
      zout.putNextEntry(new ZipEntry("/"));
      zip(zout, classesDirectory(), "", paths);
      for (String path : java.resources()) {
        File resourcesDirectory = new File(module.directory(), path);
        if (resourcesDirectory.exists()) {
          zip(zout, resourcesDirectory, "", paths);
        }
      }
      zout.finish();
      zout.close();
    } finally {
      fout.close();
    }
    Files.rename(temp, classesJar);
  }

  /**
   * Returns the most recent modification time for a file in the given
   * directory.
   */
  private long mostRecentModification(File directory) {
    long mostRecent = -1;
    if (!directory.exists()) return -1;
    for (File file : directory.listFiles()) {
      if (file.isDirectory()) {
        mostRecent = Math.max(mostRecent, mostRecentModification(file));
      } else {
        mostRecent = Math.max(mostRecent, file.lastModified());
      }
    }
    return mostRecent;
  }

  /** Jar containing the classes and resources for this module. */
  File classesJar() {
    return new File(module.outputDirectory(), "classes.jar");
  }

  /** Pre-compiled jars that are part of this module. */
  List<File> jars() throws BakeError {
    if (java.jars().length == 0) return Collections.emptyList();
    List<File> jars = Lists.newArrayList();
    for (String path : java.jars()) {
      File jarFile = new File(module.directory(), path);
      if (!jarFile.exists()) {
        throw new BakeError("File not found: " + jarFile + " (module "
            + module.name() + ")");
      }
      jars.add(jarFile);
    }
    return jars;
  }

  /**
   * Recursively zips all files in directory. Prepends file names with path.
   */
  private void zip(ZipOutputStream zout, File directory, String path,
      Set<String> paths) throws IOException {
    // Add directory entries.
    if (path.length() > 0 && paths.add(path)) {
      zout.putNextEntry(new ZipEntry(path));
    }

    byte[] buffer = new byte[8192];
    for (File file : directory.listFiles()) {
      if (file.isDirectory()) {
        zip(zout, file, path + file.getName() + "/", paths);
      } else {
        zout.putNextEntry(new ZipEntry(path + file.getName()));
        com.google.common.io.Files.copy(file, zout);
        zout.closeEntry();
      }
    }
  }

  // Tests:

  /** Runs tests in this module. */
  private void runTests() throws BakeError, IOException {
    Set<String> testClassNames = Sets.newHashSet();
    for (String sourceDirectory : java.testSource()) {
      findTestFiles(new File(module.directory(), sourceDirectory), "", testClassNames);
    }

    if (testClassNames.isEmpty()) {
      Log.i("No tests found for %s.", module.name());

      // We could run anyway...
      return;
    }

    /*
     * Note: We fork a new VM to run the tests. We don't have JUnit in the
     * current classloader, and even if we did, it would be different from
     * the copy in the test classloader. We'd have to use reflection to access
     * JUnit in the test classloader.
     */
    List<File> files = allJarsForTests();
    files.add(testClassesDirectory());
    for (String resourceDirectory : java.testResources()) {
      files.add(new File(module.directory(), resourceDirectory));
    }

    String classpath = Joiner.on(File.pathSeparatorChar).join(files);

    List<String> command = Lists.newArrayList();

    String testRunner = java.testRunner().equals("") ? "org.junit.runner.JUnitCore"
        : java.testRunner();
    command.addAll(Arrays.asList("java", "-classpath", classpath, testRunner));
    command.addAll(testClassNames);

    Log.v(command.toString());

    File workingDirectory = new File(module.directory(), java.testWorkingDirectory());
    Process process = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .directory(workingDirectory) // Run from tests directory.
        .start();

    ByteStreams.copy(process.getInputStream(), System.out);
    try {
      int result = process.waitFor();
      if (result == 0) {
        Log.i("%s passed.", module.name());
      } else {
        throw new BakeError(module.name() + " failed.");
      }
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  /** Recursively searches for classes with names ending in Test. */
  private void findTestFiles(File directory, String packageName,
      Set<String> testClassNames) {
    if (!directory.exists()) {
      Log.v("Skipping missing directory: %s",
          repository.relativePath(directory));
      return;
    }
    for (File file : directory.listFiles()) {
      String name = file.getName();
      if (file.isDirectory()) {
        findTestFiles(file, packageName + name + ".", testClassNames);
      } else {
        if (name.endsWith("Test.java")) {
          testClassNames.add(packageName
              + name.substring(0, name.length() - 5));
        }
      }
    }
  }


  // Create directories:

  /** Initializes a Java module. */
  public static void initializeModule(Repository repository,
      String moduleName) throws IOException,
      BakeError {
    // TODO: Update for new format.

    Repository.validateModuleName(moduleName);

    // Get default annotation.
    Package javaPackage = JavaHandler.class.getPackage();
    Java java = javaPackage.getAnnotation(Java.class);

    // Create main module.
    File moduleRoot = new File(repository.root(),
        moduleName.replace('.', File.separatorChar));
    Files.mkdirs(new File(moduleRoot, java.source()[0]));
    Files.mkdirs(new File(moduleRoot, java.resources()[0]));
    File testsRoot = new File(repository.root(), moduleName.replace(
        '.', File.separatorChar) + File.separatorChar + "tests");
    Files.mkdirs(new File(testsRoot, java.source()[0]));
    Files.mkdirs(new File(testsRoot, java.resources()[0]));
    String unqualifiedName = moduleName.substring(
        moduleName.lastIndexOf('.') + 1);
    File bakeFile = new File(moduleRoot,
        unqualifiedName + Repository.DOT_BAKE);
    if (!bakeFile.exists()) {
      com.google.common.io.Files.write("@bake.Java(\n"
          + "  testDependencies = {\n"
          + "      \"external:junit/junit@4.8.2\"\n"
          + "  }\n"
          + ") module " + moduleName + ";\n",
          bakeFile, Charsets.UTF_8);
    }

    Log.i("Created " + repository.relativePath(moduleRoot) + ".");
  }
}
