// Copyright 2011 Square, Inc.
package bake;

import bake.tool.BakeAnnotation;
import bake.tool.java.JavaHandler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a Java package. By convention, tests go in sub-packages named
 * {@code tests}.
 *
 * @author Bob Lee (bob@squareup.com)
 */
@Target(ElementType.PACKAGE)
@BakeAnnotation(handler = JavaHandler.class)
@Retention(RetentionPolicy.RUNTIME) // so we can read @Java on bake.tool.java
public @interface Java {

  // TODO: Support compile and run time-only dependencies? Or should those
  // go in separate packages?
  // TODO: Support excluding transitive dependencies.

  /**
   * Identifies dependencies on other Java code by package name. Included
   * during compliation and at run time.
   */
  String[] dependencies() default {};

  /**
   * Identifies the main class. If set, Bake will be an executable jar
   * containing all of this package's dependencies.
   */
  String mainClass() default "";

  /**
   * Default arguments to pass from the command line. Requires
   * {@link #mainClass()}.
   */
  String[] args() default {};

  /**
   * Published packages. Requires {@link #mainClass()}.
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
   * The software license for this package.
   */
  License license() default License.PROPRIETARY;
}
