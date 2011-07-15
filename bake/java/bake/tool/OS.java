// Copyright 2011 Square Inc.
package bake.tool;

/**
 * OS-specific utility methods.
 */
public class OS {

  /** Returns true if we're running on Windows. */
  public static boolean windows() {
    Log.v(System.getProperty("os.name"));
    return System.getProperty("os.name").toLowerCase().contains("win");
  }
}
