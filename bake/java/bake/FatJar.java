// Copyright 2011 Square, Inc.
package bake;

import bake.tool.Attribute;
import bake.tool.BakeAnnotation;
import bake.tool.java.FatJarHandler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Builds a "fat jar," a (possibly executable) jar containing all of a module's dependencies.
 *
 * @author Bob Lee (bob@squareup.com)
 */
@Target(ElementType.PACKAGE)
@BakeAnnotation(handler = FatJarHandler.class)
@Retention(RetentionPolicy.RUNTIME) // so we can read @Java on bake.tool.java
public @interface FatJar {

  /** Strategy for constructing the fat jar. */
  enum Strategy {

    /** Merges all classes and resources into one jar. */
    MERGE,

    /** Builds a One-JAR archive (http://one-jar.sourceforge.net/). */
    ONE_JAR
  }

  /**
   * Excludes dependencies from the fat jar.
   *
   * <p><b>Caveat:</b> Doesn't support transitive external dependencies yet.
   */
  String[] excludedDependencies() default {};

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
   * Attributes to include in the {@code META-INF/MANIFEST.MF} file.
   */
  Attribute[] manifestAttributes() default {};

  /**
   * The strategy to use when constructing the fat jar.
   */
  Strategy strategy() default Strategy.MERGE;
}
