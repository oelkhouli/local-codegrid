package dev.codegrid.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Estimates rank eligible workers. Resource admission is rechecked under a node row lock. */
public final class SchedulingPolicy {
  public record Candidate(
      String worker,
      String node,
      long expectedMs,
      double load,
      int cpuBudget,
      int usedCpu,
      long memoryBudget,
      long usedMemory,
      int slots,
      int usedSlots,
      boolean fresh,
      boolean compatible) {
    public boolean eligible() {
      return fresh
          && compatible
          && cpuBudget - usedCpu >= 1000
          && memoryBudget - usedMemory >= 536_870_912L
          && usedSlots < slots;
    }

    public double score() {
      return expectedMs * (1 + Math.max(0, load) / Math.max(1, cpuBudget / 1000.0))
          + usedSlots * 250;
    }
  }

  public static Optional<Candidate> choose(List<Candidate> candidates) {
    return candidates.stream()
        .filter(Candidate::eligible)
        .min(Comparator.comparingDouble(Candidate::score).thenComparing(Candidate::worker));
  }

  private SchedulingPolicy() {}
}
