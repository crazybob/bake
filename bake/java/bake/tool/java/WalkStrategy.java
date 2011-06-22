// Copyright 2011 Square, Inc.
package bake.tool.java;

/**
 * Determines whether or not to traverse test dependencies when walking the dependency graph.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public enum WalkStrategy {

  /** Never traverse test dependencies. */
  NO_TESTS,

  /** Traverse test dependencies in the current module but not transitive test dependencies. */
  CURRENT_TESTS,

  /** Always traverse test dependencies. */
  ALL_TESTS
}
