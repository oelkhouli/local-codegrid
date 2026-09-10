package dev.codegrid.worker;

import java.nio.file.*;
import java.util.*;

public class LedgerProbe {
  public static void main(String[] args) throws Exception {
    System.in.read();
    var ledger = new NodeLedger(Path.of(args[0]));
    try {
      ledger.begin(UUID.randomUUID(), 1, System.currentTimeMillis() + 30000, 1, 536870912, 1000);
      System.exit(0);
    } catch (IllegalStateException e) {
      System.exit(2);
    }
  }
}
