package com.example.starter.domain;

import java.util.Map;

/**
 * 带回溯解析的完整结果：可行时携带名称 -> 精确版本（名称升序），
 * 不可行时携带确定性的阻塞点诊断，二者恰有一个非 null。
 */
public record ResolutionResult(Map<String, Integer> solution, InfeasibleBlocker blocker) {

    public boolean feasible() {
        return solution != null;
    }

    public static ResolutionResult feasible(Map<String, Integer> solution) {
        return new ResolutionResult(solution, null);
    }

    public static ResolutionResult infeasible(InfeasibleBlocker blocker) {
        return new ResolutionResult(null, blocker);
    }
}
