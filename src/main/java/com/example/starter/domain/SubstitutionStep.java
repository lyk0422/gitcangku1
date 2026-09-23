package com.example.starter.domain;

import java.util.List;

/**
 * 锁文件中冻结的单个替代步骤解释。
 *
 * @param stepOrder          步骤序号（从 0 开始，按替代发生顺序）
 * @param originalCoordinate 被替代节点的原坐标
 * @param sourcePattern      命中规则的原坐标模式（字面量）
 * @param platform           规则的目标平台
 * @param finalCoordinate    最终采用的坐标
 * @param policyVersion      解析时读取的唯一策略版本号
 * @param rejections         被拒绝候选的快照（按优先级升序）
 */
public record SubstitutionStep(
        int stepOrder,
        String originalCoordinate,
        String sourcePattern,
        String platform,
        String finalCoordinate,
        long policyVersion,
        List<CandidateRejection> rejections) {

    public SubstitutionStep {
        rejections = rejections == null ? List.of() : List.copyOf(rejections);
    }
}
