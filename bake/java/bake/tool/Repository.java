// Copyright 2011 Square, Inc.
package bake.tool;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.inject.Guice;
import com.google.inject.Injector;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A Bake repository.
 *
 * @author Bob Lee (bob@squareup.com)
 */
@Singleton public class Repository {

  public static final String DOT_BAKE = ".bake";

  private static final String rawModuleNamePattern
      = "([a-z_]{1}[a-z0-9_]*(\\.[a-z_]{1}[a-z0-9_]*)*)";
  private static final Pattern moduleNamePattern
      = Pattern.compile(rawModuleNamePattern);

  private final File root;
  private final File output;
  private final ModuleParser moduleParser;

  private final Map<String, Module> modules = Maps.newHashMap();

  @Inject Repository(@Root File root, ModuleParser moduleParser)
      throws IOException {
    this.root = root;
    this.output = new File(root, "out");
    this.moduleParser = moduleParser;
  }

  /** Initializes a repository at the given path. */
  @SuppressWarnings("ResultOfMethodCallIgnored")
  static void initialize(String path) throws IOException {
    File root = new File(path);
    File bakeDirectory = new File(root, DOT_BAKE);
    if (!bakeDirectory.mkdirs()) {
      System.err.println("Failed to create " + bakeDirectory + ".");
      System.exit(1);
    }

    File properties = new File(bakeDirectory, "bake.properties");
    properties.createNewFile(); // Doesn't overwrite existing file.
  }

  /** Returns true if moduleName conforms to our standard. */
  public static void validateModuleName(String moduleName) throws BakeError {
    if (!moduleNamePattern.matcher(moduleName).matches()) {
      throw new BakeError("Invalid module name: " + moduleName + "."
          + " The name should match " + rawModuleNamePattern + ".");
    }
  }

  /**
   * Finds and parses the .bake file for the given module. Returns an
   * existing module if we parsed it already.
   */
  public Module moduleByName(String name) throws BakeError, IOException {
    validateModuleName(name);
    Module module = modules.get(name);
    if (module == null) {
      module = moduleParser.parse(name);
      modules.put(name, module);
    }
    return module;
  }

  /**
   * Returns the module name for the given .bake file or directory. Computes
   * the path to file from root and then replaces the path separator character
   * with '.'.
   */
  public String toModuleName(File file) throws BakeError, IOException {
    file = file.getCanonicalFile();
    String path = file.getPath();

    // Make sure file is under root.
    String rootPath = root.getPath() + File.separatorChar;

    if (!path.startsWith(rootPath)) {
      throw new BakeError(path + " is not under " + root + ".");
    }

    // Handle .bake files.
    if (file.isFile()) {
      if (!path.endsWith(DOT_BAKE)) {
        throw new BakeError("Expected a .bake file or directory: " + path);
      }

      String name = file.getName();
      name = name.substring(0, name.length() - DOT_BAKE.length());
      file = file.getParentFile();
      if (file == null || !file.getName().equals(name)) {
        throw new BakeError("Directory and .bake file should share the same"
            + " name: " + path);
      }
      path = file.getPath();
    }

    String relativePath = path.substring(rootPath.length());
    return relativePath.replace(File.separatorChar, '.');
  }

  /**
   * Bakes the modules at the given paths.
   */
  public void bakePaths(Iterable<String> paths) throws BakeError, IOException {
    List<Module> modules = new ArrayList<Module>();
    for (String path : paths) {
      Log.v("Resolving %s...", path);
      modules.add(moduleByName(toModuleName(new File(path))));
    }
    for (Module module : modules) module.bake();
  }

  /** Returns true if file is the root of a Bake repository. */
  private static boolean isRoot(File file) {
    return new File(file, DOT_BAKE).exists();
  }

  /** Finds the repository's root directory. */
  private static File findRootFrom(File workingDirectory) {
    File current = workingDirectory;
    while (current != null && !isRoot(current)) {
      current = current.getParentFile();
    }
    return current;
  }

  /**
   * Returns an output directory with the given path. Creates the directory
   * if necessary.
   */
  public File outputDirectory(String path) throws IOException {
    return Files.mkdirs(new File(output, path));
  }

  /**
   * Returns the repository's top level output directory.
   */
  public File outputDirectory() {
    return output;
  }

  /** Returns a path relative to the repository root. */
  public String relativePath(File absolute) {
    String absolutePath = absolute.getPath();
    if (!absolutePath.startsWith(root.getPath())) {
      throw new AssertionError(absolutePath + " not under " + root + ".");
    }
    return absolutePath.substring(root.getPath().length() + 1);
  }

  /**
   * Recursively finds all .bake files and builds them.
   */
  public void bakeAll() throws BakeError, IOException {
    Set<File> bakeFiles = Sets.newHashSet();
    findBakeFiles(root, bakeFiles);
    List<Module> modules = new ArrayList<Module>();
    for (File file : bakeFiles) {
      modules.add(moduleByName(toModuleName(file)));
    }
    for (Module module : modules) module.bake();
  }

  private void findBakeFiles(File directory, Set<File> bakeFiles) {
    for (File file : directory.listFiles()) {
      if (file.isDirectory()) {
        if (!new File(file, DOT_BAKE).exists()) {
          findBakeFiles(file, bakeFiles);
        } else {
          Log.v("Skipping nested Bake repo: %s", file);
        }
      } else {
        String name = file.getName();
        if (!name.equals(DOT_BAKE) && name.endsWith(DOT_BAKE)) {
          bakeFiles.add(file);
        }
      }
    }
  }

  /** Returns the root directory. */
  public File root() {
    return root;
  }

  /**
   * Builds a Bake instance.
   */
  public static class Builder {

    File workingDirectory;
    DiagnosticListener<FileObject> diagnosticListener;

    /**
     * Specifies a working directory. Defaults to the current working directory.
     * Must be a sub directory of a Bake repository.
     */
    public Builder workingDirectory(File workingDirectory) {
      this.workingDirectory = workingDirectory;
      return this;
    }

    /**
     * Listens to compiler messages.
     */
    public Builder diagnosticListener(
        DiagnosticListener<FileObject> diagnosticListener) {
      this.diagnosticListener = diagnosticListener;
      return this;
    }

    /**
     * Builds a Bake instance.
     *
     * @throws BakeError if workingDirectory is not under a Bake repository.
     */
    public Repository build() throws BakeError, IOException {
      File workingDirectory = this.workingDirectory == null
          ? new File(".") : this.workingDirectory;
      File root = findRootFrom(workingDirectory.getCanonicalFile());
      if (root == null) {
        throw new BakeError("Not in a Bake repository.");
      }
      Injector injector
          = Guice.createInjector(new BakeModule(root, diagnosticListener));
      return injector.getInstance(Repository.class);
    }
  }
}
