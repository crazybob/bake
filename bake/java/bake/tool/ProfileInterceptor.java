// Copyright 2011 Square, Inc.
package bake.tool;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

/**
 * Logs method timings.
 *
 * @author Bob Lee (bob@squareup.com)
 */
class ProfileInterceptor implements MethodInterceptor {
  public Object invoke(MethodInvocation invocation) throws Throwable {
    if (!Log.VERBOSE) return invocation.proceed();

    long start = System.nanoTime();
    try {
      return invocation.proceed();
    } finally {
      long duration = (System.nanoTime() - start) / 1000000;
      Log.v("%s: %dms", invocation.getMethod(), duration);
    }
  }
}
