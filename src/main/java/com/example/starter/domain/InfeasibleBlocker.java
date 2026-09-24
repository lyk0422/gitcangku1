package com.example.starter.domain;

import java.util.List;

/**
 * 重解析求解失败时的阻塞点诊断（确定性回溯遍历下最接近完整解的失败点）。
 *
 * @param name     导致不可行的依赖名称
 * @param reason   原因：VERSIONS_WITHDRAWN=候选均已撤回，
 *                 VERSIONS_MISSING=区间内无登记版本，
 *                 RANGE_INTERSECTION_EMPTY=多约束区间交集为空
 * @param versions 相关版本号（升序）：已撤回候选、缺失的期望版本或冲突版本
 */
public record InfeasibleBlocker(String name, String reason, List<Integer> versions) {

    public static final String VERSIONS_WITHDRAWN = "VERSIONS_WITHDRAWN";
    public static final String VERSIONS_MISSING = "VERSIONS_MISSING";
    public static final String RANGE_INTERSECTION_EMPTY = "RANGE_INTERSECTION_EMPTY";
}
