package com.example.starter.domain;

import java.util.List;

/**
 * 单个候选坐标在替代尝试中的拒绝原因快照。
 *
 * @param coordinate 候选坐标
 * @param priority   候选在规则内的优先级（1～5）
 * @param reason     稳定机器可读原因：NOT_FOUND/WITHDRAWN/UNAVAILABLE_PLATFORM/
 *                   CONSTRAINT_INCOMPATIBLE/CYCLE/NO_USABLE_VERSION
 */
public record CandidateRejection(String coordinate, int priority, String reason) {
}
