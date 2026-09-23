package com.example.starter.domain;

/**
 * 替代规则中的一个候选坐标及其在规则内的优先级（从 1 开始，数字越小优先级越高）。
 */
public record SubstitutionCandidate(String coordinate, int priority) {

    public SubstitutionCandidate {
        coordinate = coordinate == null ? "" : coordinate.trim();
        if (coordinate.isEmpty()) {
            throw new IllegalArgumentException("替代候选坐标不能为空");
        }
        if (priority < 1 || priority > 5) {
            throw new IllegalArgumentException("替代候选优先级必须在 1～5 之间: " + priority);
        }
    }
}
