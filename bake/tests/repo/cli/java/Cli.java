// Copyright 2011 Square, Inc.

import com.google.common.io.ByteStreams;
import java.io.IOException;

/** @author Bob Lee (bob@squareup.com) */
public class Cli {

  public static void main(String[] args) throws InterruptedException {
    new Thread() {
      @Override public void run() {
        try {
          ByteStreams.copy(System.in, System.out);
        } catch (IOException e) {
          throw new AssertionError(e);
        }
        System.exit(0);
      }
    }.start();

    Thread.sleep(5000);
    throw new AssertionError("Timed out trying to read stdin.");
  }
}
