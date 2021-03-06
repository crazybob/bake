// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.tool.BakeError;
import bake.tool.Log;
import bake.tool.Module;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import org.apache.commons.codec.binary.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Builds an executable jar containing all of its necessary dependencies.
 *
 * @author Bob Lee (bob@squareup.com)
 */
abstract class ExecutableJar {

  private static final String SCRIPT_PREFIX = "#!/bin/sh\n"
      + "set -u\n" // Require variables to be set.
      + "TEMP_FILE=`mktemp -t bake.XXXXXXXXXX` || exit 1\n" // Exit if temp file creation fails.
      + "CHILD_PID=0\n"
      + "function exitChild() {\n"
      + "  if [ $CHILD_PID = 0 ]\n"
      + "  then\n"
      + "    EXIT_CODE=1\n" // Child hasn't started yet.
      + "  else\n"
      + "    wait $CHILD_PID\n"
      + "    EXIT_CODE=$?\n"
      + "  fi\n"
      + "  rm -f $TEMP_FILE\n"
      + "  exit $EXIT_CODE\n"
      + "}\n"
      + "trap 'kill 0; exitChild' INT TERM\n"
      + "cat <<\"EOF\" | openssl enc -d -base64 > $TEMP_FILE\n";

  private static final String SCRIPT_SUFFIX = "EOF\n"
      + "java $VM_ARGS -jar $TEMP_FILE $ARGS \"$@\" < /dev/stdin &\n"
      + "CHILD_PID=$!\n"
      + "exitChild\n";

  final JavaHandler handler;

  ExecutableJar(JavaHandler handler) {
    this.handler = handler;
  }

  /**
   * Makes the jar containing all of its dependencies.
   */
  abstract void makeJar() throws BakeError, IOException;

  boolean baked;

  void bake() throws BakeError, IOException {
    if (baked) return;
    makeJar();
    makeExecutable();
    baked = true;
  }

  void makeExecutable() throws IOException {
    File executable = new File(handler.repository.outputDirectory("bin"),
        handler.module.name());
    File jarFile = jarFile();
    if (jarFile.lastModified() <= executable.lastModified()) {
      Log.v("%s is up to date.", handler.repository.relativePath(executable));
      return;
    }

    File temp = new File(executable.getPath() + ".temp");

    FileOutputStream fout = new FileOutputStream(temp);
    try {
      writeScriptPrefixTo(fout);
      byte[] encoded = Base64.encodeBase64(
          Files.toByteArray(jarFile), true);
      fout.write(encoded);
      writeScriptSuffixTo(fout);
    } finally {
      fout.close();
    }

    Log.v("chmod +x " + temp.getPath());
    Process chmod = new ProcessBuilder("chmod", "+x", temp.getPath())
        .redirectErrorStream(true)
        .start();
    ByteStreams.copy(chmod.getInputStream(), System.out);

    temp.renameTo(executable);
  }

  /** Returns the path for the One-Jar executable jar. */
  File jarFile() throws IOException {
    return new File(handler.repository.outputDirectory("jars"),
        handler.module.name() + ".jar");
  }

  /** Writes a script that makes a jar directly executable. */
  private void writeScriptPrefixTo(OutputStream out) {
    try {
      out.write(SCRIPT_PREFIX.getBytes("UTF-8"));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  /** Writes a script that makes a jar directly executable. */
  private void writeScriptSuffixTo(OutputStream out) {
    String suffix = SCRIPT_SUFFIX
        .replace("$VM_ARGS", join(handler.java.vmArgs()))
        .replace("$ARGS", join(handler.java.args()));
    try {
      out.write(suffix.getBytes("UTF-8"));
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

  protected List<String> getClassPathStrings() {
    List<String> classPathJars = Lists.newArrayList();
    for (ExternalArtifact.Id id : handler.externalProvidedDependencies()) {
      classPathJars.add(id.name + ".jar");
    }

    for (Module module : handler.internalProvidedDependencies()) {
      try {
        for (File jar : module.javaHandler().jars()) {
          classPathJars.add(jar.getName());
        }

        try {
          File jar = module.javaHandler().executableJar.jarFile();
          classPathJars.add(jar.getName());
        } catch (IOException ioe) {
        }
      } catch (BakeError bakeError) {
        Log.w("Problem accessing javaHandler for module %s: %s", module.name(), bakeError);
      }
    }
    return classPathJars;
  }
}
