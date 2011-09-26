// Copyright 2011 Square, Inc.
package bake.tool.android;

import bake.Android;
import bake.tool.BakeError;
import bake.tool.Handler;
import bake.tool.Module;
import bake.tool.java.JavaHandler;

import javax.inject.Inject;
import java.io.IOException;

/**
 * Handles Android app modules.
 *
 * @author Bob Lee (bob@squareup.com)
 */
public class AndroidHandler implements Handler<Android> {

  private final Android android;
  private final JavaHandler javaHandler;

  @Inject AndroidHandler(Module module, Android android) {
    this.android = android;
    this.javaHandler = (JavaHandler) module.newHandlerFor(android.java());
  }

  public Android annotation() {
    return android;
  }

  public void bake(boolean runTests) throws IOException, BakeError {
    // TODO: Process resources.

    javaHandler.bake(runTests);

    // TODO: Make APK.
  }
}
