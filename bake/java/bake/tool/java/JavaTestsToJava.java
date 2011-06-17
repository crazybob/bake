// Copyright 2011 Square, Inc.
package bake.tool.java;

import bake.Java;
import bake.JavaTests;
import bake.License;
import bake.Sdk;

import java.lang.annotation.Annotation;

/**
 * Adapts Java to JavaTests.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class JavaTestsToJava implements Java {

  final JavaTests tests;

  public JavaTestsToJava(JavaTests tests) {
    this.tests = tests;
  }

  @Override
  public String[] dependencies() {
    return tests.dependencies();
  }

  @Override
  public String mainClass() {
    return "";
  }

  @Override
  public String[] args() {
    return new String[] {};
  }

  @Override
  public String[] vmArgs() {
    return new String[] {};
  }

  @Override
  public String[] jars() {
    return new String[] {};
  }

  @Override
  public String[] source() {
    return tests.source();
  }

  @Override
  public String[] resources() {
    return tests.resources();
  }

  @Override
  public Sdk sdk() {
    return tests.sdk();
  }

  @Override
  public License license() {
    return tests.license();
  }

  @Override
  public Class<? extends Annotation> annotationType() {
    return Java.class;
  }
}
