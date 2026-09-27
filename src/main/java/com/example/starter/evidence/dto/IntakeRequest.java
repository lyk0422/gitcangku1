package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证物入库请求。保管人取自 X-Actor-Id，初始状态 SEALED。
 *
 * @param commandKey   幂等命令键
 * @param evidenceKey  证物业务键，全局唯一
 * @param caseKey      所属案件键
 * @param category     证物类别
 * @param sealNo       封条编号
 * @param locationCode 初始库位编码，可空；非空时库位必须存在且为 ACTIVE，初始库位版本号为 1
 */
public record IntakeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String evidenceKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotBlank @Size(max = 64) String category,
        @NotBlank @Size(max = 64) String sealNo,
        @Size(max = 64) String locationCode) {

    /**
     * 兼容不指定初始库位的入库请求（库位为空、版本号为 0）。
     */
    public IntakeRequest(String commandKey, String evidenceKey, String caseKey,
                         String category, String sealNo) {
        this(commandKey, evidenceKey, caseKey, category, sealNo, null);
    }
}
