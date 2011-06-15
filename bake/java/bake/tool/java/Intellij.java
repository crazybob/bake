// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.Java;
import bake.tool.BakeError;
import bake.tool.BakePackage;
import bake.tool.Log;
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
import java.util.Map;
import java.util.Set;

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

  // TODO: Generate module groups. Use top-level directories?
  // TODO: Inline modules that have jars only?

  final Repository repository;
  final JavaHandler handler;
  final File ideaDirectory;

  Intellij(JavaHandler handler) {
    this.repository = handler.repository;
    this.handler = handler;
    ideaDirectory = new File(repository.root(), ".idea");

    // IntelliJ appears to use '-' in its module file names.
    BakePackage bakePackage = handler.bakePackage;
  }

  /** Returns the path to the module XML file for the given package. */
  private File moduleXmlFor(BakePackage bakePackage) {
    return new File(bakePackage.directory(),
      bakePackage.name().replace('.', '-') + ".iml");
  }

  /**
   * Generates
   */
  void bake() throws BakeError, IOException {
    if (!ideaDirectory.exists()) {
      Log.v("IntelliJ project not found.");
      return;
    }

    // Write module XML files.
    Set<BakePackage> allPackages = handler.allPackages();
    for (BakePackage bakePackage : allPackages) {
      writeModuleXml(bakePackage);
    }

    // Add module files to ./idea/modules.xml.
    addModules(allPackages);
  }

  /** Adds modules to the modules.xml file. */
  private void addModules(final Set<BakePackage> allPackages)
      throws IOException, BakeError {
    XMLReader xmlReader;
    try {
      xmlReader = new ModulesXmlFilter(
          XMLReaderFactory.createXMLReader(), allPackages);
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
      return out.toString();
    } catch (TransformerConfigurationException e) {
      throw new AssertionError(e);
    } catch (TransformerException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Temporary. Used to ensure we don't overwrite a baked module XML with a
   * transitive module's XML.
   */
  private static Set<BakePackage> baked = Sets.newHashSet();

  /** Writes the module XML file for the given package. */
  private void writeModuleXml(BakePackage bakePackage) throws IOException,
      BakeError {
    if (baked.contains(bakePackage)) return;

    /*
     * We include only first order dependencies in the module XML. Does
     * IntelliJ include transitive dependencies in the run time classpath?
     * That would be the right thing for it to do.
     */

    JavaHandler handler = bakePackage.javaHandler();
    Java java = handler.java;
    File temp = new File(bakePackage.outputDirectory(), "temp.iml");

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
        // It shouldn't be necessary to set isTestSource to true for test
        // packages. I think that's only used when deciding which jar deps
        // to include.
        if (new File(bakePackage.directory(), sourceDirectory).exists()) {
          out.write("      <sourceFolder url=\"file://$MODULE_DIR$/" +
              sourceDirectory + "\" isTestSource=\"false\" />\n");
        }
      }
      out.write("    </content>\n");

      out.write("    <orderEntry type=\"inheritedJdk\" />\n");
      out.write("    <orderEntry type=\"sourceFolder\"" +
          " forTests=\"false\" />\n");

      // Add resource directories. This will include them in the classpath
      // if you run something through IntelliJ.
      for (String resourceDirectory : java.resources()) {
        if (new File(bakePackage.directory(), resourceDirectory).exists()) {
          writeOrderEntry(out, bakePackage, new File(bakePackage.directory(),
              resourceDirectory), null, false, null);
        }
      }

      // Add internal module dependencies.
      for (String dependency : java.dependencies()) {
        if (!ExternalDependency.isExternal(dependency)) {
          out.write("    <orderEntry type=\"module\" module-name=\"" +
              dependency.replace('.', '-') + "\" />\n");
        }
      }

      // Write local jars. We export these as if they're part of the module.
      for (File jar : handler.jars()) {
        // By convention, look for source jars suffixed with "-src.jar".
        String jarPath = jar.getPath();
        String srcPath = jarPath.substring(0, jarPath.length() - 4)
            + "-src.jar";
        writeOrderEntry(out, bakePackage, jar, new File(srcPath), true, null);
      }

      // Write external dependencies.
      Map<ExternalArtifact.Id, ExternalArtifact> externalArtifacts
          = this.handler.externalArtifacts(); // From root package.
      Set<ExternalArtifact.Id> firstOrder = Sets.newHashSet();
      for (ExternalDependency externalDependency
          : handler.externalDependencies()) {
        ExternalArtifact.Id jarId = externalDependency.jarId();
        firstOrder.add(jarId);
        ExternalArtifact classes = externalArtifacts.get(jarId);
        ExternalArtifact source
            = externalArtifacts.get(externalDependency.sourceId());
        writeOrderEntry(out, bakePackage, classes.file,
            source == null ? null : source.file, false, null);
      }

      // TODO: Inspect the Ivy results and do this for transitive modules, too.
      if (bakePackage == this.handler.bakePackage) {
        // Add transitive external dependencies. We add them in the "runtime"
        // scope because we can only compile against first order deps. This
        // enables us to run apps directly from IntelliJ.
        baked.add(bakePackage);
        Log.v("Adding additional dependencies for root package...");
        for (ExternalArtifact externalArtifact
            : handler.externalArtifacts().values()) {
          if (externalArtifact.id.type == ExternalArtifact.Type.JAR
              && !firstOrder.contains(externalArtifact.id)) {
            ExternalArtifact source
                = externalArtifacts.get(externalArtifact.sourceId());
            writeOrderEntry(out, bakePackage, externalArtifact.file,
                source == null ? null : source.file, false, "RUNTIME");
          } else {
            Log.v("Already added %s.", externalArtifact.file);
          }
        }
      }

      out.write("  </component>\n");
      out.write("</module>\n");

      // Commit.
      out.close();
      File moduleXml = moduleXmlFor(bakePackage);
      temp.renameTo(moduleXml);
      Log.v("Wrote %s.", repository.relativePath(moduleXml));
    } finally {
      fout.close();
    }
  }

  private void writeOrderEntry(Writer out, BakePackage bakePackage,
      File classes, File source, boolean exported, String scope)
      throws IOException {
    out.write("    <orderEntry type=\"module-library\"");
    if (exported) out.write(" exported=\"\"");
    if (scope != null) out.write(" scope=\"" + scope + "\"");
    out.write(">\n");
    out.write("      <library>\n");
    out.write("        <CLASSES>\n");
    writeRoot(out, bakePackage, classes);
    out.write("        </CLASSES>\n");
    out.write("        <JAVADOC />\n");

    if (source == null) {
      out.write("        <SOURCES />\n");
    } else {
      out.write("        <SOURCES>\n");
      writeRoot(out, bakePackage, source);
      out.write("        </SOURCES>\n");
    }

    out.write("      </library>\n");
    out.write("    </orderEntry>\n");
  }

  private void writeRoot(Writer out, BakePackage bakePackage, File file)
      throws IOException {
    if (file == null) return;
    if (!file.exists()) {
      Log.v("Not found: %s", repository.relativePath(file));
      return;
    }

    String path = bakePackage.relativePath(file);
    if (file.isDirectory()) {
      out.write("          <root url=\"file://$MODULE_DIR$/"
          + path + "\" />\n");
    } else {
      // Assume it's a jar.
      out.write("          <root url=\"jar://$MODULE_DIR$/"
          + path + "!/\" />\n");
    }
  }

  /** Adds missing modules to the modules.xml file. */
  private class ModulesXmlFilter extends XMLFilterImpl {
    private final Set<String> modulePaths;

    public ModulesXmlFilter(XMLReader xmlReader, Set<BakePackage> allPackages) {
      super(xmlReader);

      modulePaths = Sets.newTreeSet(); // Sorted.
      for (BakePackage bakePackage : allPackages) {
        modulePaths.add("$PROJECT_DIR$/"
            + repository.relativePath(moduleXmlFor(bakePackage)));
      }
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
