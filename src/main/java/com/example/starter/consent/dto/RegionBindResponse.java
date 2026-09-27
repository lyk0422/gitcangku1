package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 区域绑定响应。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      授权代次
 * @param regionCode 绑定的驻留区域代码
 */
public record RegionBindResponse(String subjectKey, Purpose purpose, int epoch, String regionCode) {
}
