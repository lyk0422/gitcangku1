package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证物入库请求。保管人取自 X-Actor-Id，初始状态 SEALED。
 * digest 与 syntheticText 为原件数字摘要与合成文本元数据，可空；未登记时不得签发阅检副本。
 *
 * @param commandKey    幂等命令键
 * @param evidenceKey   证物业务键，全局唯一
 * @param caseKey       所属案件键
 * @param category      证物类别
 * @param sealNo        封条编号
 * @param digest        原件数字摘要（如 SHA-256 十六进制），可空
 * @param syntheticText 原件合成文本元数据，可空
 */
public record IntakeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String evidenceKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotBlank @Size(max = 64) String category,
        @NotBlank @Size(max = 64) String sealNo,
        @Size(max = 128) String digest,
        @Size(max = 4000) String syntheticText) {

    /**
     * 不登记数字摘要与合成文本的入库请求（digest/syntheticText 为 null）。
     */
    public IntakeRequest(String commandKey, String evidenceKey, String caseKey,
                         String category, String sealNo) {
        this(commandKey, evidenceKey, caseKey, category, sealNo, null, null);
    }
}