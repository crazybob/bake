// Copyright 2011 Square, Inc.
package bake;

import bake.tool.android.AndroidHandler;
import bake.tool.BakeAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * An Android app.
 * 
 * @author Bob Lee (bob@squareup.com)
 */
@Target(ElementType.PACKAGE)
@BakeAnnotation(handler = AndroidHandler.class)
public @interface Android {

  // TODO

  /**
   * An Android project contains a Java library. The Android handler
   * generates source code, builds the Java library, and then builds the
   * final Android artifacts.
   */
  Java java() default @Java(
      // Follows Android's conventions.
      source = { "src", "gen" }
  );
}

