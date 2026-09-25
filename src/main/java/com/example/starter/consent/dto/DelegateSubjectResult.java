package com.example.starter.consent.dto;

import java.util.List;
import java.util.Map;

import com.example.starter.consent.Purpose;

/**
 * 批次查询中单个主体的结果：固化命中的委托与授权版本。
 *
 * @param subjectKey      主体标识
 * @param delegateKey     命中的委托键
 * @param delegateVersion 固化的委托版本
 * @param epochs          固化的授权代次（用途 -> 代次）
 * @param records         当前代次下的记录列表，按用途名与记录键排序
 */
public record DelegateSubjectResult(
        String subjectKey,
        String delegateKey,
        int delegateVersion,
        Map<Purpose, Integer> epochs,
        List<DelegateRecordView> records) {
}
