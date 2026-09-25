package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 聚合记录视图：按主体、用途、epoch 聚合时的每条记录，标明所属子范围及当前是否可用。
 *
 * <p>已撤回（整体撤回或子范围独立撤回）的记录仍会返回，usable=false，不做静默过滤；
 * scopeKey 为 null 表示默认子范围。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      记录所属授权代次
 * @param scopeKey   所属子范围标识；null 表示默认子范围
 * @param recordKey  记录键
 * @param payload    记录内容（合成字符串）
 * @param usable     是否可用：代次有效且所属子范围未独立撤回时为 true
 */
public record RecordViewResponse(String subjectKey, Purpose purpose, int epoch,
                                 String scopeKey, String recordKey, String payload, boolean usable) {
}
