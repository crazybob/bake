// Copyright 2011 Square, Inc.
package bake;

import bake.tool.BakeAnnotation;
import bake.tool.java.JavaHandler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a Java module. By convention, tests go in sub-modules named
 * {@code tests}.
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
   * during compliation and at run time.
   */
  String[] dependencies() default {};

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
}
