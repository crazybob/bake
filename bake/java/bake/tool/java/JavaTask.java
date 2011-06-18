// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.tool.BakeError;

import java.io.IOException;

/** A task that can be executed against a Java handler. */
public interface JavaTask {

  /** Executes this task against the given handler. */
  void execute(JavaHandler handler) throws BakeError, IOException;
}
