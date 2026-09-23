package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 一次锁定解析的完整结果：必选阶段得到的精确集合（含后续纳入的可选闭包），
 * 以及每条可选依赖的 included/skipped 结论。
 *
 * @param repositoryVersion 读取快照时的仓库版本号
 * @param chosen            名称 -> 精确版本（名称升序），包含根、全部必选依赖与成功纳入的可选依赖闭包
 * @param optionalResults   可选依赖结论，按“来源名称、依赖名称”字典序稳定排列
 */
public record LockResolution(
        long repositoryVersion,
        Map<String, Integer> chosen,
        List<OptionalResolution> optionalResults) {

    /** 可选依赖是否被纳入。 */
    public enum OptionalStatus {
        /** 已选版本满足区间，或新版本连同必选闭包成功加入。 */
        INCLUDED,
        /** 无法在不改变已选集合的前提下纳入。 */
        SKIPPED
    }

    /**
     * 单条可选依赖的解析结论。
     *
     * @param selectedVersion INCLUDED 时纳入（或已选）的精确版本；SKIPPED 时为 null
     * @param reason          SKIPPED 的稳定原因；INCLUDED 时为 null
     */
    public record OptionalResolution(
            String sourceName,
            String dependencyName,
            OptionalStatus status,
            Integer selectedVersion,
            SkipReason reason) {

        public static OptionalResolution included(String sourceName, String dependencyName, int version) {
            return new OptionalResolution(sourceName, dependencyName, OptionalStatus.INCLUDED, version, null);
        }

        public static OptionalResolution skipped(String sourceName, String dependencyName, SkipReason reason) {
            return new OptionalResolution(sourceName, dependencyName, OptionalStatus.SKIPPED, null, reason);
        }
    }

    /** 可选依赖跳过的稳定原因。 */
    public enum SkipReason {
        /** 区间内不存在未撤回且支持目标平台的候选版本。 */
        NO_COMPATIBLE_CANDIDATE,
        /** 存在候选版本，但其必选闭包无法在不改变已选集合的前提下满足。 */
        CLOSURE_INFEASIBLE,
        /** 目标名称已选，但已选版本不满足该可选依赖区间，且不能更换已选版本。 */
        SELECTED_VERSION_OUT_OF_RANGE
    }
}
