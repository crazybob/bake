// Copyright 2011 Square, Inc.
package bake.tool.android;

import bake.Android;
import bake.tool.BakeError;
import bake.tool.Module;
import bake.tool.Handler;
import bake.tool.java.JavaHandler;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Collection;

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

  @Override public Collection<Module> directDependencies() throws BakeError, IOException {
    return javaHandler.directDependencies();
  }

  public void bake() throws IOException, BakeError {
    // TODO: Process resources.

    javaHandler.bake();

    // TODO: Make APK.
  }
}
