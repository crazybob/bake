// Copyright 2011 Square, Inc.
package bake.tool;

import java.io.File;
import java.io.IOException;

/**
 * File utilities.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class Files {

  /**
   * Creates the given directory if necessary and returns it.
   */
  public static File mkdirs(File directory) throws IOException {
    if (!directory.exists() && !directory.mkdirs()) {
      throw new IOException("Failed to create " + directory + ".");
    }
    return directory;
  }

  /**
   * Renames from to to. Throws an exception if the rename fails.
   */
  public static void rename(File from, File to) throws IOException {
    if (!from.renameTo(to)) {
      throw new IOException(
          "Failed to rename " + from + " to " + to + ".");
    }
  }
}
