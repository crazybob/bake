// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.tool.BakeError;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.Set;

/**
 * Determines whether or not to traverse test dependencies when walking the dependency graph.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public enum WalkStrategy {

  /** Never traverse test dependencies. */
  EXCLUDING_TESTS {
    @Override Set<String> directDependenciesFor(JavaHandler handler) throws BakeError, IOException {
      return handler.mainDependencies();
    }
  },

  /** Always traverse test dependencies. */
  INCLUDING_TESTS {
    @Override Set<String> directDependenciesFor(JavaHandler handler) throws BakeError, IOException {
      return handler.allDependencies();
    }
  },

  /** Traverses exports. See {@link bake.Java#exports()}. */
  EXPORTS {
    @Override Set<String> directDependenciesFor(JavaHandler handler) throws BakeError, IOException {
      return Sets.newHashSet(handler.java.exports());
    }
  };

  /** Returns dependencies that should be followed from the given handler. */
  abstract Set<String> directDependenciesFor(JavaHandler handler) throws BakeError, IOException;
}
