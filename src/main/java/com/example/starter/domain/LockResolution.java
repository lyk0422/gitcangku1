package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 一次锁定解析的完整结果：必选闭包 + 可选依赖评估明细。
 *
 * @param repositoryVersion    读取快照时的仓库版本号
 * @param targetPlatform       锁定目标平台（os/arch）
 * @param chosen               最终精确集合：名称 -> 版本（名称升序），含必选与成功加入的可选
 * @param optionalOutcomes     可选依赖评估结果，按“来源名称、依赖名称”字典序稳定排列
 */
public record LockResolution(
        long repositoryVersion,
        String targetPlatform,
        Map<String, Integer> chosen,
        List<OptionalDependencyOutcome> optionalOutcomes) {
}
