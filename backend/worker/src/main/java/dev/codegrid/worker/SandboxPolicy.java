package dev.codegrid.worker;

import java.util.*;

/** No user-controlled image, host path, executable, compiler flag or shell argument. */
public final class SandboxPolicy {
  public static final long MEMORY = 536870912L;

  public static List<String> create(
      String engine,
      String node,
      UUID job,
      int generation,
      int index,
      long deadline,
      String image) {
    if (!Set.of("docker", "podman").contains(engine)
        || !node.matches("[A-Za-z0-9_-]{1,64}")
        || !image.matches("sha256:[a-f0-9]{64}")
        || generation < 1
        || generation > 3
        || index < 0
        || index > 8) throw new IllegalArgumentException("Invalid trusted execution metadata");
    return List.of(
        engine,
        "create",
        "--interactive",
        "--name",
        name(job, generation, index),
        "--label",
        "codegrid.managed=true",
        "--label",
        "codegrid.node=" + node,
        "--label",
        "codegrid.attempt=" + key(job, generation),
        "--label",
        "codegrid.deadline=" + deadline,
        "--network",
        "none",
        "--user",
        "65534:65534",
        "--read-only",
        "--cap-drop",
        "ALL",
        "--security-opt",
        "no-new-privileges:true",
        "--cpus",
        "1.0",
        "--memory",
        "512m",
        "--memory-swap",
        "512m",
        "--pids-limit",
        "128",
        "--ulimit",
        "nofile=128:128",
        "--ulimit",
        "fsize=33554432:33554432",
        "--shm-size",
        "16m",
        "--log-driver",
        "none",
        "--tmpfs",
        "/work:rw,exec,nosuid,nodev,size=67108864,mode=1777",
        "--tmpfs",
        "/tmp:rw,noexec,nosuid,nodev,size=16777216,mode=1777",
        "--workdir",
        "/work",
        "--entrypoint",
        "/usr/bin/timeout",
        image,
        "--signal=KILL",
        "20s",
        "/usr/bin/python3",
        "-I",
        "/opt/codegrid/phase.py");
  }

  public static String name(UUID job, int generation, int index) {
    return "cg-" + key(job, generation) + "-" + index;
  }

  public static String key(UUID job, int generation) {
    return job + "-" + generation;
  }

  private SandboxPolicy() {}
}
