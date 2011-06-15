// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.tool.BakeError;
import bake.tool.BakePackage;
import bake.tool.Files;
import bake.tool.Log;
import bake.tool.Profile;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Creates a One-Jar archive. See http://one-jar.sourceforge.net/.
 *
 * @author Bob Lee (bob@squareup.com)
 */
class OneJar { // TODO: Extend ExecutableJar.

  /**
   * If we prepend a jar with this script, that jar will be directly
   * executable.
   */
  // -DuseJavaUtilZip addresses
  // http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6865530.
  private static final String SCRIPT = "#!/bin/sh\n"
      + "set -e\n"
      + "exec java -DuseJavaUtilZip $VM_ARGS -jar \"$0\" $ARGS \"$@\"\n";

  final JavaHandler handler;

  OneJar(JavaHandler handler) {
    this.handler = handler;
  }

  /**
   * Creates an executable jar containing all of this package's dependencies.
   */
  @Profile void bake() throws BakeError, IOException {
    // Maps paths (in zip) to files.
    Map<String, File> files = Maps.newHashMap();

    // One-Jar puts main.jar at the front of the classpath.
    files.put("main/main.jar", handler.classesJar());

    addExternalDependenciesTo(files);
    addInternalDependenciesTo(files);

    File oneJarFile = oneJarFile();

    // Skip if everything is up-to-date.
    if (oneJarFile.exists()) {
      long lastModified = -1;
      for (File file : files.values()) {
        lastModified = Math.max(lastModified, file.lastModified());
      }

      // The last time the dependency list was modified.
      lastModified = Math.max(lastModified, handler.external.lastModified());

      if (lastModified <= oneJarFile.lastModified()) {
        Log.i("%s is up to date.", handler.repository.relativePath(oneJarFile));
        return;
      }
    }

    Log.i("Building %s...", handler.repository.relativePath(oneJarFile));
    Log.v("Files: %s", files);

    File temp = new File(oneJarFile.getPath() + ".temp");
    FileOutputStream fout = new FileOutputStream(temp);
    try {
      writeScriptTo(fout);
      ZipOutputStream zout = new JarOutputStream(
          new BufferedOutputStream(fout), oneJarManifest());
      copyOneJarBootTo(zout);
      zip(zout, files);
      zout.finish();
      zout.close();
    } finally {
      fout.close();
    }

    Log.v("chmod +x " + temp.getPath());
    Process chmod = new ProcessBuilder("chmod", "+x", temp.getPath())
        .redirectErrorStream(true)
        .start();
    ByteStreams.copy(chmod.getInputStream(), System.out);

    Files.rename(temp, oneJarFile);
  }

  /** Writes a script that makes a jar directly executable. */
  private void writeScriptTo(FileOutputStream fout) {
    String script = SCRIPT.replace("$VM_ARGS", join(handler.java.vmArgs()))
      .replace("$ARGS", join(handler.java.args()));
    try {
      fout.write(script.getBytes("UTF-8"));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private String join(String[] args) {
    List<String> filtered = Lists.newArrayListWithCapacity(args.length);
    // TODO: More escaping?
    for (String arg : args) filtered.add("\"" + arg + "\"");
    return Joiner.on(' ').join(filtered);
  }

  private void addInternalDependenciesTo(Map<String, File> files)
      throws BakeError, IOException {
    Set<BakePackage> allPackages = handler.allPackages();
    for (BakePackage bakePackage : allPackages) {
      String baseName = "internal-" + bakePackage.name();
      // Skip main classes jar. We store this in main/main.jar.
      if (bakePackage != handler.bakePackage) {
        files.put("lib/" + baseName + ".jar",
            bakePackage.javaHandler().classesJar());
      }
      for (File jar : bakePackage.javaHandler().jars()) {
        files.put("lib/" + baseName + "-" + jar.getName(), jar);
      }
    }
  }

  /** Maps external dependencies to jars inside of our One-Jar archive. */
  private void addExternalDependenciesTo(Map<String, File> files) {
    for (ExternalArtifact externalArtifact
        : handler.externalArtifacts().values()) {
      ExternalArtifact.Id id = externalArtifact.id;
      if (id.type == ExternalArtifact.Type.JAR) {
        files.put("lib/" + id.organization + "-" + id.name + ".jar",
            externalArtifact.file);
      }
    }
  }

  /** Zips the given files using the map keys as the paths inside the zip. */
  private void zip(ZipOutputStream zout, Map<String, File> files)
      throws IOException {
    for (Map.Entry<String, File> entry : files.entrySet()) {
      File file = entry.getValue();
      if (!file.exists()) {
        Log.v("Skipping missing file: %s",
            handler.repository.relativePath(file));
        continue;
      }
      zout.putNextEntry(new ZipEntry(entry.getKey()));
      com.google.common.io.Files.copy(file, zout);
      zout.closeEntry();
    }
  }

  /** Copies One-Jar's classes into our jar. */
  private void copyOneJarBootTo(ZipOutputStream zout) throws IOException {
    InputStream in = getClass().getResourceAsStream("one-jar-boot.jar");
    try {
      copy(new ZipInputStream(new BufferedInputStream(in)), zout);
    } finally {
      in.close();
    }
  }

  /** Returns the manifest for our One-Jar archive. */
  private Manifest oneJarManifest() {
    Manifest manifest = new Manifest();
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    Attributes attributes = manifest.getMainAttributes();

    // If we forget the version, Manifest will write a blank file!!! What a
    // terrible API. :-(
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");

    attributes.put(new Attributes.Name("Main-Class"),
        "com.simontuffs.onejar.Boot");
    attributes.put(new Attributes.Name("One-Jar-Main-Class"),
        handler.java.mainClass());

    // This factory is necessary for Guice and Ivy to work. We may want to
    // make this configurable.
    // TODO: Specify manifest attributes in .bake files. Put defaults here.
    attributes.put(new Attributes.Name("One-Jar-URL-Factory"),
        "com.simontuffs.onejar.JarClassLoader$OneJarURLFactory");

    return manifest;
  }

  /** Copies one zip into another. */
  private static void copy(ZipInputStream in, ZipOutputStream out)
      throws IOException {
    ZipEntry entry;
    while ((entry = in.getNextEntry()) != null) {
      out.putNextEntry(new ZipEntry(entry.getName()));
      ByteStreams.copy(in, out);
      out.closeEntry();
    }
  }

  /** Returns the path for the One-Jar executable jar. */
  private File oneJarFile() throws IOException {
    return new File(handler.repository.outputDirectory("bin"),
        handler.bakePackage.name());
  }
}
