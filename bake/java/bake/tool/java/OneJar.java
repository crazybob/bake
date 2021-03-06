// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.tool.BakeError;
import bake.tool.Files;
import bake.tool.Log;
import bake.tool.Module;
import bake.tool.Profile;
import com.google.common.base.Joiner;
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
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static bake.tool.java.WalkStrategy.EXCLUDING_TESTS;

/**
 * Creates a One-Jar archive. See http://one-jar.sourceforge.net/.
 *
 * @author Bob Lee (bob@squareup.com)
 */
class OneJar extends ExecutableJar {

  /**
   * If we prepend a jar with this script, that jar will be directly
   * executable.
   */
  OneJar(JavaHandler handler) {
    super(handler);
  }

  /**
   * Creates an executable jar containing all of this module's dependencies.
   */
  @Profile @Override void makeJar() throws BakeError, IOException {
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
      lastModified = Math.max(lastModified, handler.externalDependencies.lastModified());

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
      ZipOutputStream zout = new JarOutputStream(
          new BufferedOutputStream(fout), manifest());
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

  private void addInternalDependenciesTo(final Map<String, File> files) throws BakeError,
      IOException {
    handler.walk(new JavaTask() {
      @Override public void execute(JavaHandler handler) throws BakeError, IOException {
        Module module = handler.module;
        if (!OneJar.this.handler.internalProvidedDependencies().contains(module)) {
          String baseName = "internal-" + module.name();
          // Skip main classes jar. We store this in main/main.jar.
          if (module != handler.module) {
            files.put("lib/" + baseName + ".jar", handler.classesJar());
          }
          for (File jar : handler.jars()) {
            files.put("lib/" + baseName + "-" + jar.getName(), jar);
          }
        }
      }

      @Override public String description() {
        return "gathering internal jars for";
      }
    }, EXCLUDING_TESTS);
  }

  /** Maps external dependencies to jars inside of our One-Jar archive. */
  private void addExternalDependenciesTo(Map<String, File> files) {
    for (ExternalArtifact externalArtifact : handler.externalDependencies.main().values()) {
      ExternalArtifact.Id id = externalArtifact.id;
      if (id.type == ExternalArtifact.Type.JAR &&
          !handler.externalProvidedDependencies().contains(id)) {
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
  private Manifest manifest() {
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

    List<String> classPathJars = getClassPathStrings();
    if (!classPathJars.isEmpty()) {
      attributes.put(new Attributes.Name("Class-Path"), Joiner.on(" ").join(classPathJars));
    }

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
    return new File(handler.repository.outputDirectory("jars"),
        handler.module.name() + ".jar");
  }
}
