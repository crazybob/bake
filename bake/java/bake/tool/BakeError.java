// Copyright 2011 Square, Inc.
package bake.tool;

/**
 * Thrown when Bake encounters an unrecoverable error.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class BakeError extends Exception {

  // TODO: Keep track of a stack.

  public BakeError(String message) {
    super(message);
  }

  /**
   * Wraps this exception in a runtime exception. Useful in cases where
   * you can't throw a checked exception (in a callback, for example). Used
   * in conjunction with {@link #unwrap(Exception)}.
   */
  public RuntimeException unchecked() {
    return new RuntimeException(this);
  }
  
  /**
   * Finds and returns a nested BakeError.
   */
  public static BakeError unwrap(Exception e) {
    Throwable current = e;
    while (current != null) {
      if (current instanceof BakeError) return (BakeError) current;
      current = current.getCause();
    }
    return null;
  }
}
