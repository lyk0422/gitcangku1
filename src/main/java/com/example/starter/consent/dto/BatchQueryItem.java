package com.example.starter.consent.dto;

/**
 * 跨对象查询结果项：固化对象的代次、驻留区域与授权版本。
 *
 * @param recordKey      记录键
 * @param epoch          对象所属授权代次
 * @param regionCode     对象驻留区域；未绑定区域为 null
 * @param consentVersion 授权版本
 * @param payload        记录内容（合成字符串）
 */
public record BatchQueryItem(String recordKey, int epoch, String regionCode,
                             int consentVersion, String payload) {
}
