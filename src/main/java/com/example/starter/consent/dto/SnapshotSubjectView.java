package com.example.starter.consent.dto;

import java.util.List;
import java.util.Map;

import com.example.starter.consent.Purpose;

/**
 * 批次查询快照中单个主体的固化版本信息。
 *
 * @param subjectKey      主体标识
 * @param delegateKey     命中的委托键
 * @param delegateVersion 固化的委托版本
 * @param epochs          固化的授权代次（用途 -> 代次）
 */
public record SnapshotSubjectView(
        String subjectKey,
        String delegateKey,
        int delegateVersion,
        Map<Purpose, Integer> epochs) {
}
