package com.example.starter.incident.plan;

/**
 * 任务依赖边键（有向：前置任务 → 后继任务），用于三方合并比较与环检测。
 */
public record EdgeKey(String fromTaskId, String toTaskId) {

    /**
     * 无向对键：用于识别同一对任务上的反向边冲突（一侧加 A→B、另一侧加 B→A）。
     */
    public String pairKey() {
        return fromTaskId.compareTo(toTaskId) <= 0
                ? fromTaskId + "->" + toTaskId
                : toTaskId + "->" + fromTaskId;
    }
}
