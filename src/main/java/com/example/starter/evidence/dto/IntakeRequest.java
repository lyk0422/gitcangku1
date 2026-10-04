package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证物入库请求。保管人取自 X-Actor-Id，初始状态 SEALED；
 * 数字摘要与合成文本元数据随入库冻结，之后不可修改。
 *
 * @param commandKey    幂等命令键
 * @param evidenceKey   证物业务键，全局唯一
 * @param caseKey       所属案件键
 * @param category      证物类别
 * @param sealNo        封条编号
 * @param digest        原件数字摘要（如 SHA-256 十六进制）
 * @param syntheticText 原件合成文本元数据，仅供授权阅检副本读取
 */
public record IntakeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String evidenceKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotBlank @Size(max = 64) String category,
        @NotBlank @Size(max = 64) String sealNo,
        @NotBlank @Size(max = 128) String digest,
        @NotBlank @Size(max = 8192) String syntheticText) {
}