// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.tool.BakeError;
import bake.tool.Files;
import bake.tool.Log;
import bake.tool.Module;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static bake.tool.java.WalkStrategy.EXCLUDING_TESTS;

/**
 * Creates a fat jar that contains all of the dependencies.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class FatJarHandler extends ExecutableJar {

  FatJarHandler(JavaHandler handler) {
    super(handler);
  }

  /**
   * Creates an executable jar containing all of this module's dependencies.
   */
  void makeJar() throws BakeError, IOException {
    final List<File> jars = Lists.newArrayList();

    // Put this module's classes and its resources first.
    jars.add(handler.classesJar());
    for (File jar : handler.jars()) jars.add(jar);

    handler.walk(new JavaTask() {
      @Override public void execute(JavaHandler handler) throws BakeError, IOException {
        Module module = handler.module;
        if (handler != FatJarHandler.this.handler &&
            !FatJarHandler.this.handler.internalProvidedDependencies().contains(module)) {
          jars.add(handler.classesJar());
          for (File jar : handler.jars()) {
            jars.add(jar);
          }
        }
      }

      @Override public String description() {
        return "gathering internal jars for";
      }
    }, EXCLUDING_TESTS);

    for (ExternalArtifact externalArtifact : handler.externalDependencies.main().values()) {
      ExternalArtifact.Id id = externalArtifact.id;
      if (id.type == ExternalArtifact.Type.JAR &&
          !handler.externalProvidedDependencies().contains(id)) {
        jars.add(externalArtifact.file);
      }
    }

    File fatJarFile = jarFile();

    // Skip if everything is up-to-date.
    if (fatJarFile.exists()) {
      long lastModified = -1;
      for (File jar : jars) {
        lastModified = Math.max(lastModified, jar.lastModified());
      }

      // The last time the dependency list was modified.
      lastModified = Math.max(lastModified, handler.externalDependencies.lastModified());

      if (lastModified <= fatJarFile.lastModified()) {
        Log.i("%s is up to date.", handler.repository.relativePath(fatJarFile));
        return;
      }
    }

    Log.i("Building %s...", handler.repository.relativePath(fatJarFile));
    Log.v("Files: %s", jars);

    File temp = new File(fatJarFile.getPath() + ".temp");
    FileOutputStream fout = new FileOutputStream(temp);
    try {
      ZipOutputStream zout = new JarOutputStream(
          new BufferedOutputStream(fout), manifest());
      zout.putNextEntry(new ZipEntry("/"));
      for (File jar : jars) copy(jar, zout);
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

    Files.rename(temp, fatJarFile);
  }

  /** Returns the manifest for our One-Jar archive. */
  private Manifest manifest() {
    Manifest manifest = new Manifest();
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    Attributes attributes = manifest.getMainAttributes();

    // If we forget the version, Manifest will write a blank file!!! What a
    // terrible API. :-(
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");

    attributes.put(new Attributes.Name("Main-Class"), handler.java.mainClass());

    List<String> classPathJars = getClassPathStrings();
    if (!classPathJars.isEmpty()) {
      attributes.put(new Attributes.Name("Class-Path"), Joiner.on(" ").join(classPathJars));
    }

    return manifest;
  }

  /** Paths that have been added to the jar. */
  final Set<String> files = Sets.newHashSet("META-INF/MANIFEST.MF");

  /** Names of directory entries. */
  final Set<String> directories = Sets.newHashSet("/");

  /** Copies one zip into another. */
  private void copy(File jar, ZipOutputStream out)
      throws IOException {
    if (!jar.exists()) {
      Log.v("%s doesn't exist.", jar);
      return;
    }
    FileInputStream fin = new FileInputStream(jar);
    try {
      ZipInputStream in = new ZipInputStream(new BufferedInputStream(fin));
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        String name = entry.getName();
        if (name.matches("(?i)META-INF/(INDEX.LIST|.+\\.(SF|DSA))"))
          continue; // Skip signatures and an index

        if (files.add(name)) {
          // Handle directory entries.
          if (name.endsWith("/")) {
            if (directories.add(name)) out.putNextEntry(new ZipEntry(name));
            continue;
          }

          // Add directory entry if necessary.
          int last = name.lastIndexOf('/');
          if (last > -1) {
            // Include the trailing '/'.
            String directory = name.substring(0, last + 1);
            if (directories.add(directory)) {
              out.putNextEntry(new ZipEntry(directory));
            }
          }

          // Copy a file.
          out.putNextEntry(new ZipEntry(name));
          ByteStreams.copy(in, out);
          out.closeEntry();
        } else {
          Log.v("%s is already present.", entry.getName());
        }
      }
    } finally {
      fin.close();
    }
  }
}
