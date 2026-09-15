package dev.codegrid.domain;

public enum Language {
  JAVA("Main.java", 6000),
  PYTHON("main.py", 1000),
  CPP("main.cpp", 4000),
  JAVASCRIPT("main.js", 1500);
  public final String filename;
  public final long coldEstimateMs;

  Language(String filename, long coldEstimateMs) {
    this.filename = filename;
    this.coldEstimateMs = coldEstimateMs;
  }

  public String image() {
    return "local-codegrid/runner-" + name().toLowerCase() + ":1";
  }
}
