// Copyright 2011 Square, Inc.
package bake.tool.android;

import bake.Android;
import bake.tool.BakeError;
import bake.tool.BakePackage;
import bake.tool.Handler;
import bake.tool.java.JavaHandler;

import javax.inject.Inject;
import java.io.IOException;

/**
 * Handles Android app packages.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class AndroidHandler implements Handler<Android> {

  private final Android android;
  private final JavaHandler javaHandler;

  @Inject AndroidHandler(BakePackage bakePackage, Android android) {
    this.android = android;
    this.javaHandler = (JavaHandler) bakePackage.newHandlerFor(android.java());
  }

  public Android annotation() {
    return android;
  }

  public void bake() throws IOException, BakeError {
    // TODO: Process resources.

    javaHandler.bake();

    // TODO: Make APK.
  }
}