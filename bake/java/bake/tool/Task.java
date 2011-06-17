// Copyright 2011 Square, Inc.
package bake.tool;

import java.io.IOException;

/** A task that can be executed against a module. */
public interface Task {

  /** Executes this task against the given module. */
  void execute(Module module) throws BakeError, IOException;
}
