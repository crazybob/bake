// Copyright 2011 Square, Inc.

/** @author Bob Lee (bob@squareup.com) */
public class Sleep {

  public static void main(String[] args) throws InterruptedException {
    Thread.sleep(Integer.parseInt(args[0]) * 1000);
  }
}
