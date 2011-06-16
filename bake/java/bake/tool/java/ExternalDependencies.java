// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.tool.*;
import bake.tool.Module;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.ivy.Ivy;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.IvyNode;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.retrieve.RetrieveOptions;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.util.AbstractMessageLogger;
import org.apache.ivy.util.Message;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * External dependencies for a Java module. This class isolates Bake from
 * Ivy.
 *
 * @author Bob Lee (bob@squareup.com)
 */
class ExternalDependencies {

  static {
    Message.setDefaultLogger(new IvyLogger());
  }

  final JavaHandler javaHandler;
  final Module module;
  final Repository repository;

  ExternalDependencies(JavaHandler javaHandler) {
    this.javaHandler = javaHandler;
    this.module = javaHandler.module;
    this.repository = javaHandler.repository;
  }

  /**
   * Transitively resolves all external dependencies. Returns references to the
   * jars.
   */
  Map<ExternalArtifact.Id, ExternalArtifact> retrieveAll()
      throws BakeError, IOException {
    Set<String> allExternalDependencies = allExternalDependencies();
    IvyResults ivyResults = readIvyResults();
    if (ivyResults != null
        && allExternalDependencies.equals(ivyResults.allExternalDependencies)) {
      Log.i("External dependencies are up to date.");
      return ivyResults.artifacts;
    }

    Log.i("Retrieving external dependencies...");
    
    writeIvyXml();

    try {
      // TODO: How do we force the download of source jars for transitive
      // dependencies?

      ModuleRevisionId id = ModuleRevisionId.newInstance(
          "internal", module.name(), "working");

      // TODO: Skip this if dependency list hasn't changed.
      Ivy ivy = newIvy();
      ResolveReport report = ivy.resolve(id, new ResolveOptions(), true);

      if (report.hasError()) {
        // Ivy should have logged any errors.
        throw new BakeError("Failed to resolve external dependencies for "
            + module.name() + ".");
      }

      /*
       * Copy artifacts from local cache to build directory. All Bake modules
       * share the same directory.
       *
       * Note: It's important that we use the module ID returned by resolve().
       * It creates a pseudo module (named "{module-name}-caller") that will
       * be re-used here.
       */
      File ivyDirectory = repository.outputDirectory("ivy/libs");
      ivy.retrieve(report.getModuleDescriptor().getModuleRevisionId(),
          ivyDirectory.getPath()
              + "/[organization]/[module]/[type]/[artifact]-[revision].[ext]",
          new RetrieveOptions());

      @SuppressWarnings("unchecked")
      List<IvyNode> nodes = report.getDependencies();
      Map<ExternalArtifact.Id, ExternalArtifact> artifacts
          = nodesToArtifacts(nodes, ivyDirectory);

      writeIvyResults(new IvyResults(allExternalDependencies, artifacts));
      
      return artifacts;
    } catch (ParseException e) {
      throw new AssertionError(e);
    }
  }

  /** Creates one or more artifacts for each node. */
  private Map<ExternalArtifact.Id, ExternalArtifact> nodesToArtifacts(
      List<IvyNode> nodes, File ivyDirectory) {
    Map<ExternalArtifact.Id, ExternalArtifact> artifacts = Maps.newTreeMap();
    for (IvyNode node : nodes) {
      if (node.isCompletelyEvicted()) {
        Log.v("Skipping evicted node: %s", node.getModuleId());
        continue;
      }

      Log.v("Node: %s", node.getModuleId());
      for (Artifact artifact : node.getAllArtifacts()) {
        Log.v("Artifact: %s", artifact);

        ModuleRevisionId revisionId = artifact.getModuleRevisionId();

        // This matches the pattern above.
        String path = revisionId.getOrganisation()
            + "/" + revisionId.getModuleId().getName()
            + "/" + artifact.getType()
            + "/" + artifact.getName()
            + "-" + revisionId.getRevision()
            + "." + artifact.getExt();
        File file = new File(ivyDirectory, path);

        ExternalArtifact.Type type = ExternalArtifact.Type.fromIvyName(
            artifact.getType());
        ExternalArtifact.Id artifactId = new ExternalArtifact.Id(
            revisionId.getOrganisation(), revisionId.getName(), type);
        if (artifacts.put(artifactId, new ExternalArtifact(artifactId, file))
            != null) {
          throw new AssertionError(
              "Two artifacts returned for " + artifactId + ".");
        }
      }
    }
    return artifacts;
  }

  /** Constucts Ivy. */
  private Ivy newIvy() throws IOException {
    IvySettings settings = new IvySettings();
    settings.setBaseDir(ivyDirectory());
    settings.setVariable("internal.repository.dir",
        ivyDirectory().getAbsolutePath());
    Ivy ivy = Ivy.newInstance(settings);
    try {
      ivy.configure(getClass().getResource("ivy-settings.xml"));
    } catch (ParseException e) {
      throw new AssertionError(e);
    }
    settings.setDefaultResolver("default");
    return ivy;
  }

  /**
   * Returns the set of all external dependencies. If this stays the same,
   * we can avoid running Ivy (right?).
   */
  private Set<String> allExternalDependencies() throws BakeError, IOException {
    Set<Module> visited = Sets.newHashSet();
    Set<String> dependencies = Sets.newHashSet();
    addExternalDependencies(visited, dependencies, module);
    return dependencies;
  }

  private void addExternalDependencies(Set<Module> visited,
      Set<String> dependencies, Module module) throws BakeError,
      IOException {
    if (visited.contains(module)) return;
    visited.add(module);
    for (String dependency : module.javaHandler().java.dependencies()) {
      if (ExternalDependency.isExternal(dependency)) {
        dependencies.add(dependency);
      } else {
        Module otherModule = repository.moduleByName(dependency);
        addExternalDependencies(visited, dependencies, otherModule);
      }
    }
  }

  private boolean wroteIvyXml;

  /**
   * Transitively writes dummy Ivy XML files (one per Bake module) that can be
   * used to resolve external dependencies.
   */
  private void writeIvyXml() throws IOException, BakeError {
    // TODO: Add information about this bake file to BakeErrors.
    // TODO: Escape XML (just in case).

    if (wroteIvyXml) return;
    wroteIvyXml = true;

    // Write Ivy XML for this module.
    File ivyDirectory = ivyDirectory();
    File ivyFile = new File(ivyDirectory, module.name() + ".xml");

    OutputStreamWriter out = new OutputStreamWriter(
        new FileOutputStream(ivyFile), "UTF-8");
    try {
      out.write("<ivy-module version=\"2.0\">\n");

      // Use "internal" as the org for internal modules.
      out.write("<info organisation=\"internal\" module=\""
          + module.name() + "\" revision=\"working\"/>\n");

      // We don't use Ivy to manage internal artifacts. The 'publications'
      // element must be explicit, or else Ivy assumes one default artifact.
      out.write("<publications/>\n");

      out.write("<dependencies>\n");
      for (String dependency : javaHandler.java.dependencies()) {
        if (ExternalDependency.isExternal(dependency)) {
          ExternalDependency ed = ExternalDependency.parse(dependency);
          out.write("<dependency org=\"" + ed.organization + "\""
              + " name=\"" + ed.name + "\""
              + " rev=\"" + ed.ivyVersion() + "\""
              + " conf=\"*->default,compile,runtime,sources\""
              + "/>\n");
        } else {
          out.write("<dependency org=\"internal\" name=\"" + dependency
              + "\" rev=\"working\" changing=\"true\"/>\n");
        }
      }
      out.write("</dependencies>\n");

      out.write("</ivy-module>\n");
    } finally {
      out.close();
    }

    // Write Ivy XML transitively.
    for (String dependency : javaHandler.java.dependencies()) {
      if (!ExternalDependency.isExternal(dependency)) {
        Module other = repository.moduleByName(dependency);
        other.javaHandler().external.writeIvyXml();
      }
    }
  }

  private File ivyDirectory() throws IOException {
    return repository.outputDirectory("ivy/xml"); // .bake/ivy
  }

  File ivyResultsFile() {
    return new File(module.outputDirectory(), "ivy.results");
  }

  /** Persisted Ivy results. */
  static class IvyResults implements Serializable {

    final Set<String> allExternalDependencies;
    final Map<ExternalArtifact.Id, ExternalArtifact> artifacts;

    IvyResults(Set<String> allExternalDependencies,
        Map<ExternalArtifact.Id, ExternalArtifact> artifacts) {
      this.allExternalDependencies = allExternalDependencies;
      this.artifacts = artifacts;
    }
  }

  IvyResults readIvyResults() throws IOException {
    File file = ivyResultsFile();
    if (!file.exists()) return null;
    FileInputStream fin = new FileInputStream(file);
    try {
      return (IvyResults) new ObjectInputStream(
          new BufferedInputStream(fin)).readObject();
    } catch (ClassNotFoundException e) {
      throw new AssertionError(e);
    } finally {
      fin.close();
    }
  }

  /** Returns the last time the dependencies were modified. */
  long lastModified() {
    return ivyResultsFile().lastModified();
  }

  void writeIvyResults(IvyResults results) throws IOException {
    File file = ivyResultsFile();
    File temp = new File(file.getPath() + ".temp");
    FileOutputStream fout = new FileOutputStream(temp);
    try {
      ObjectOutputStream oout
          = new ObjectOutputStream(new BufferedOutputStream(fout));
      oout.writeObject(results);
      oout.close();
    } finally {
      fout.close();
    }
    Files.rename(temp, file);
  }

  private static class IvyLogger extends AbstractMessageLogger {

    private static final int LOG_LEVEL = Message.MSG_INFO;
    private static final String LOG_PREFIX = LogPrefixes.IVY;

    int counter = 0;

    @Override protected void doProgress() {
      if (counter++ == 0) {
        System.out.print(LOG_PREFIX);
      }

      if (System.console() == null) {
        if (counter % 10 == 0) System.out.print('.');
      } else {
        // Supposedly we're outputting a terminal. Safe to assume backspace
        // works?
        if (counter > 1) printBackspace();
        System.out.print(indicator());
      }
    }

    private static void printBackspace() {
      System.out.print('\b');
    }

    /** Produces an indeterminate spinner. */
    char indicator() {
      switch (counter % 5) {
        case 0: return '|';
        case 1: return '/';
        case 2: return '-';
        case 3: return '|';
        case 4: return '\\';
        default: throw new AssertionError();
      }
    }

    @Override protected void doEndProgress(String message) {
      if (System.console() != null) printBackspace();
      counter = 0;
      System.out.println(message);
    }

    public void log(String message, int level) {
      if (level <= LOG_LEVEL) {
        System.out.println(LOG_PREFIX + message);
      }
    }

    public void rawlog(String message, int level) {
      log(message, level);
    }
  }
}
