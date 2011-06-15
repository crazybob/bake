// Copyright 2011 Square, Inc.
package bake.tool;

import java.io.PrintStream;

/**
 * Bake log.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class Log {

  public static boolean VERBOSE = false;

  private static final String PREFIX = LogPrefixes.BAKE;

  public static void v(String message, Object... args) {
    if (VERBOSE) log(System.out, message, args);
  }

  public static void i(String message, Object... args) {
    log(System.out, message, args);
  }
  
  public static void w(String message, Object... args) {
    log(System.err, message, args);
  }

  public static void e(String message, Object... args) {
    log(System.err, message, args);
  }

  private static void log(PrintStream out, String message, Object... args) {
    out.printf(PREFIX + message + "\n", args);
  }
}
