// Copyright 2011 Square, Inc.
package bake.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Apply to methods that should be profiled.
 *
 * @author Bob Lee (bob@squareup.com)
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Profile {}
