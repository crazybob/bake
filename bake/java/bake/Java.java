// Copyright 2011 Square, Inc.
package bake;

import bake.tool.BakeAnnotation;
import bake.tool.java.JavaHandler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a Java module.
 *
 * @author Bob Lee (bob@squareup.com)
 */
@Target(ElementType.PACKAGE)
@BakeAnnotation(handler = JavaHandler.class)
@Retention(RetentionPolicy.RUNTIME) // so we can read @Java on bake.tool.java
public @interface Java {

  // TODO: Support compile and run time-only dependencies? Or should those
  // go in separate modules?
  // TODO: Support excluding transitive dependencies.

  /**
   * Identifies dependencies on other Java code by module name. Included
   * during compilation and at run time.
   */
  String[] dependencies() default {};

  /**
   * Identifies dependencies on other Java code by module name. Included
   * during compilation and at run time.
   */
  String[] providedDependencies() default {};

  // TODO: Support inheriting the version from dependencies().

  /**
   * Dependencies this modules exports as if they were part of this module. Useful for creating
   * aliases and for batch dependency inclusion. Exclusively provides dependencies to other modules.
   * Does not affect the dependencies used to compile or run this module.
   */
  String[] exports() default {};

  /**
   * Identifies the main class. If set, Bake will be an executable jar
   * containing all of this module's dependencies.
   */
  String mainClass() default "";

  /**
   * Default arguments to pass from the command line. Requires
   * {@link #mainClass()}.
   */
  String[] args() default {};

  /**
   * VM arguments. Requires {@link #mainClass()}.
   */
  String[] vmArgs() default { "-Xmx1G" };

  /**
   * Pre-compiled jars. Relative to module directory.
   */
  String[] jars() default {};

  /**
   * Java source directories. Relative to module directory.
   */
  String[] source() default { "java" };

  /**
   * Resource directory. The files will be included in the jar. Relative to
   * module directory.
   */
  String[] resources() default { "resources" };

  /**
   * SDK to build against.
   */
  Sdk sdk() default Sdk.JAVA;

  /**
   * The software license for this module.
   */
  License license() default License.PROPRIETARY;

  // Tests

  /**
   * Identifies test dependencies on other Java code by module name. Included
   * during compilation and at run time. Extends {@link #dependencies()}.
   */
  String[] testDependencies() default {};

  /**
   * Test source directory. Relative to module directory.
   */
  String[] testSource() default { "tests/java" };

  /**
   * Test resource directory. Relative to module directory. Included in classpath
   * when running tests.
   */
  String[] testResources() default { "tests/resources" };

  /**
   * Main class used to run tests. Bake will pass test class names to this class on the
   * command line.
   */
  String testRunner() default "";

  /**
   * Working directory to use when running tests.
   */
  String testWorkingDirectory() default "";

  /**
   * Build a OneJar output instead of FatJar. This is necessary if any dependencies are signed.
   */
  boolean oneJar() default false;

// TODO: Support this:
//  /**
//   * Matches test source files that Bake should run. Matches against a path relative to the
//   * module root.
//   */
//  String[] testIncludes() default { ".*Test\\.java" };
}
