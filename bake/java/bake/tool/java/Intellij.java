// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.Java;
import bake.tool.BakeError;
import bake.tool.Log;
import bake.tool.Module;
import bake.tool.Repository;
import com.google.common.base.Charsets;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.Attributes2Impl;
import org.xml.sax.helpers.XMLFilterImpl;
import org.xml.sax.helpers.XMLReaderFactory;

import javax.inject.Inject;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static bake.tool.java.ExternalDependency.isExternal;
import static bake.tool.java.WalkStrategy.ALL_TESTS;

/**
 * Generates IntelliJ IDEA configurations. Bake currently supports IntelliJ's
 * directory-based configuration. Doesn't touch other modules that were not
 * generated by Bake. Assumes that the IntelliJ project root and Bake
 * repository root are the same.
 *
 * @author Bob Lee (bob@squareup.com)
 */
class Intellij {
  
  private static final String MODULE = "module";
  private static final String TEST_SCOPE = "TEST";
  private static final String RUNTIME_SCOPE = "RUNTIME";

  // TODO: Generate module groups. Use top-level directories?
  // TODO: Inline modules that have jars only?

  final Repository repository;
  final Module module;
  JavaHandler handler;
  final Java java;
  final File ideaDirectory;

  @Inject Intellij(Repository repository, Module module, Java java) {
    this.repository = repository;
    this.module = module;
    this.java = java;
    ideaDirectory = new File(repository.root(), ".idea");
  }

  void setHandler(JavaHandler handler) {
    this.handler = handler;
  }

  /** Returns the path to the module XML file for the given module. */
  private File moduleXmlFor(Module module) {
    return new File(module.directory(),
      module.name().replace('.', '-') + ".iml");
  }

  /**
   * Updates the IntelliJ configuration.
   */
  void updateAll() throws BakeError, IOException {
    if (!ideaDirectory.exists()) {
      Log.v("IntelliJ project not found.");
      return;
    }

    // Write module XML files.
    handler.walk(new JavaTask() {
      @Override public void execute(JavaHandler handler) throws BakeError, IOException {
        handler.intellij.writeModuleXml();
      }
    }, ALL_TESTS);

    // Add module files to ./idea/modules.xml.
    updateModulesXml();
  }

  /** Adds modules to the modules.xml file. */
  private void updateModulesXml() throws IOException, BakeError {
    XMLReader xmlReader;
    try {
      xmlReader = new ModulesXmlFilter(XMLReaderFactory.createXMLReader());
    } catch (SAXException e) {
      throw new AssertionError(e);
    }

    // Update modules.xml.
    File modulesXmlFile = new File(ideaDirectory, "modules.xml");
    String originalXml = Files.toString(modulesXmlFile, Charsets.UTF_8);
    String newXml = xmlToString(xmlReader, new InputSource(
        new StringReader(originalXml)));

    Log.v("original: %s", originalXml);
    Log.v("new: %s", newXml);

    // TODO: We currently parse and update this file once per test module.
    // Do it just once total.
    File temp = new File(modulesXmlFile.getPath() + ".temp");
    Files.write(newXml, temp, Charsets.UTF_8);
    temp.renameTo(modulesXmlFile);
    Log.i("Updated %s.", repository.relativePath(modulesXmlFile));
  }

  /**
   * Reads XML from inputSource, parses it with xmlReader, and then transforms
   * the result to a string.
   */
  private String xmlToString(XMLReader xmlReader, InputSource inputSource) {
    try {
      Transformer transformer = TransformerFactory.newInstance()
          .newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      StringWriter out = new StringWriter();
      StreamResult result = new StreamResult(out);
      SAXSource source = new SAXSource(xmlReader, inputSource);
      transformer.transform(source, result);

      // Match IntelliJ's formatting.
      out.write("\n"); // new line at EOF.
      return out.toString().replaceAll("([^ ])/>", "$1 />");
    } catch (TransformerConfigurationException e) {
      throw new AssertionError(e);
    } catch (TransformerException e) {
      throw new AssertionError(e);
    }
  }

  /** Writes the module XML file for the given module. */
  private void writeModuleXml() throws IOException,
      BakeError {
    File temp = new File(module.outputDirectory(), "temp.iml");

    // Note: Module XML files don't support $PROJECT_DIR$.
    FileOutputStream fout = new FileOutputStream(temp);
    try {
      Writer out = new OutputStreamWriter(new BufferedOutputStream(fout),
          "UTF-8");

      out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      out.write("<module type=\"JAVA_MODULE\" version=\"4\">\n");
      out.write("  <component name=\"NewModuleRootManager\"" +
          " inherit-compiler-output=\"true\">\n");
      out.write("    <exclude-output />\n");

      // Add source directories.
      out.write("    <content url=\"file://$MODULE_DIR$\">\n");
      for (String sourceDirectory : java.source()) {
        writeSourceDirectory(out, sourceDirectory, false);
      }
      for (String sourceDirectory : java.testSource()) {
        writeSourceDirectory(out, sourceDirectory, true);
      }
      out.write("    </content>\n");

      out.write("    <orderEntry type=\"inheritedJdk\" />\n");
      out.write("    <orderEntry type=\"sourceFolder\"" +
          " forTests=\"false\" />\n");

      // Add resource directories. This will include them in the classpath
      // if you run something through IntelliJ.
      writeResourceDirectories(out, java.resources(), null);
      writeResourceDirectories(out, java.testResources(), "TEST");
 
      // Add internal module dependencies.
      writeModuleDependencies(out, handler.mainDependencies(), null);
      writeModuleDependencies(out, handler.testDependencies(), TEST_SCOPE);

      // Write local jars. We export these as if they're part of the module.
      for (File jar : handler.jars()) {
        // By convention, look for source jars suffixed with "-src.jar".
        String jarPath = jar.getPath();
        String srcPath = jarPath.substring(0, jarPath.length() - 4)
            + "-src.jar";
        writeOrderEntry(out, module, jar, new File(srcPath), true, null);
      }

      // Write external dependencies.
      Set<ExternalArtifact.Id> added = Sets.newHashSet();

      // These are used at compile time.
      writeExternalDependencies(out, added, handler.externalDependencies(), null);
      writeExternalDependencies(out, added, handler.externalTestDependencies(), TEST_SCOPE);

      // Add transitive external dependencies. We add them in the "runtime"
      // scope because we can only compile against first order deps. This
      // enables us to run apps directly from IntelliJ.
      writeRuntimeDependencies(out, added, handler.externalDependencies.main().values(),
          RUNTIME_SCOPE);

      // IntelliJ really should have a "test runtime" scope.
      writeRuntimeDependencies(out, added, handler.externalDependencies.test().values(),
          TEST_SCOPE);

      out.write("  </component>\n");
      out.write("</module>\n");

      // Commit.
      out.close();
      File moduleXml = moduleXmlFor(module);
      if (!temp.renameTo(moduleXml)) {
        throw new IOException("Rename failed.");
      }
      Log.v("Wrote %s.", repository.relativePath(moduleXml));
    } finally {
      fout.close();
    }
  }

  private void writeRuntimeDependencies(Writer out, Set<ExternalArtifact.Id> added,
      Collection<ExternalArtifact> artifacts, String scope) throws IOException {
    Map<ExternalArtifact.Id, ExternalArtifact> allArtifacts = handler.externalDependencies.all();
    for (ExternalArtifact externalArtifact : artifacts) {
      if (externalArtifact.id.type == ExternalArtifact.Type.JAR
          && !added.contains(externalArtifact.id)) {
        ExternalArtifact source = allArtifacts.get(externalArtifact.sourceId());
        writeOrderEntry(out, module, externalArtifact.file,
            source == null ? null : source.file, false, scope);
      } else {
        Log.v("Already added %s.", externalArtifact.file);
      }
    }
  }

  private void writeExternalDependencies(Writer out, Set<ExternalArtifact.Id> added,
      Iterable<ExternalDependency> dependencies, String scope) throws IOException {
    Map<ExternalArtifact.Id, ExternalArtifact> allArtifacts = handler.externalDependencies.all();
    for (ExternalDependency externalDependency : dependencies) {
      ExternalArtifact.Id jarId = externalDependency.jarId();
      added.add(jarId);
      ExternalArtifact classes = allArtifacts.get(jarId);
      ExternalArtifact source = allArtifacts.get(externalDependency.sourceId());
      writeOrderEntry(out, module, classes.file, source == null ? null : source.file, false,
          scope);
    }
  }

  private void writeModuleDependencies(Writer out, Set<String> dependencies,
      String scope) throws IOException {
    for (String dependency : dependencies) {
      if (!isExternal(dependency)) {
        out.write("    <orderEntry type=\"module\" module-name=\"" +
            dependency.replace('.', '-') + "\"");
        if (scope != null) {
          out.write(" scope=\"TEST\"");
        }
        out.write(" />\n");
      }
    }
  }

  private void writeResourceDirectories(Writer out, String[] resourceDirectories,
      String scope) throws IOException {
    for (String resourceDirectory : resourceDirectories) {
      if (new File(module.directory(), resourceDirectory).exists()) {
        writeOrderEntry(out, module, new File(module.directory(),
            resourceDirectory), null, false, scope);
      }
    }
  }

  private void writeSourceDirectory(Writer out, String sourceDirectory,
      boolean tests) throws IOException {
    if (new File(module.directory(), sourceDirectory).exists()) {
      out.write("      <sourceFolder url=\"file://$MODULE_DIR$/" +
          sourceDirectory + "\" isTestSource=\"" + tests + "\" />\n");
    }
  }

  private void writeOrderEntry(Writer out, Module module,
      File classes, File source, boolean exported, String scope)
      throws IOException {
    out.write("    <orderEntry type=\"module-library\"");
    if (exported) out.write(" exported=\"\"");
    if (scope != null) out.write(" scope=\"" + scope + "\"");
    out.write(">\n");
    out.write("      <library>\n");
    out.write("        <CLASSES>\n");
    writeRoot(out, module, classes);
    out.write("        </CLASSES>\n");
    out.write("        <JAVADOC />\n");

    if (source == null) {
      out.write("        <SOURCES />\n");
    } else {
      out.write("        <SOURCES>\n");
      writeRoot(out, module, source);
      out.write("        </SOURCES>\n");
    }

    out.write("      </library>\n");
    out.write("    </orderEntry>\n");
  }

  private void writeRoot(Writer out, Module module, File file)
      throws IOException {
    if (file == null) return;
    if (!file.exists()) {
      Log.v("Not found: %s", repository.relativePath(file));
      return;
    }

    String path = module.relativePath(file);
    if (file.isDirectory()) {
      out.write("          <root url=\"file://$MODULE_DIR$/"
          + path + "\" />\n");
    } else {
      // Assume it's a jar.
      out.write("          <root url=\"jar://$MODULE_DIR$/" + path + "!/\" />\n");
    }
  }

  /** Adds missing modules to the modules.xml file. */
  private class ModulesXmlFilter extends XMLFilterImpl {
    private final Set<String> modulePaths;

    public ModulesXmlFilter(XMLReader xmlReader) throws BakeError, IOException {
      super(xmlReader);

      modulePaths = Sets.newTreeSet(); // Sorted.
      handler.walk(new JavaTask() {
        @Override public void execute(JavaHandler handler) throws BakeError, IOException {
          modulePaths.add("$PROJECT_DIR$/"
              + repository.relativePath(moduleXmlFor(handler.module)));
        }
      }, ALL_TESTS);
    }

    @Override
    public void startElement(String uri, String localName, String qName,
        Attributes atts) throws SAXException {
      // Remove any modules that are already present.
      if (localName.equals(MODULE)) {
        String path = atts.getValue("filepath");
        modulePaths.remove(path);
      }

      super.startElement(uri, localName, qName, atts);
    }

    @Override
    public void endElement(String uri, String localName, String qName)
        throws SAXException {
      if (localName.equals("modules")) {
        // Add missing modules.
        for (String modulePath : modulePaths) {
          Attributes2Impl attributes = new Attributes2Impl();
          attributes.addAttribute(null, null, "fileurl", "CDATA",
              "file://" + modulePath);
          attributes.addAttribute(null, null, "filepath", "CDATA",
              modulePath);

          super.startElement(null, null, MODULE, attributes);
          super.endElement(null, null, MODULE);
        }
      }

      super.endElement(uri, localName, qName);
    }
  }
}
