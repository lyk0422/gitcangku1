package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证物入库请求。保管人取自 X-Actor-Id，初始状态 SEALED。
 * locationCode 可空：提供时证物直接放入该库位（库位须存在且为 ACTIVE），缺省表示未分配库位。
 *
 * @param commandKey   幂等命令键
 * @param evidenceKey  证物业务键，全局唯一
 * @param caseKey      所属案件键
 * @param category     证物类别
 * @param sealNo       封条编号
 * @param locationCode 初始库位业务键；null 表示未分配库位
 */
public record IntakeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String evidenceKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotBlank @Size(max = 64) String category,
        @NotBlank @Size(max = 64) String sealNo,
        @Size(max = 64) String locationCode) {

    /**
     * 不指定初始库位的入库请求（兼容既有调用）。
     */
    public IntakeRequest(String commandKey, String evidenceKey, String caseKey,
                         String category, String sealNo) {
        this(commandKey, evidenceKey, caseKey, category, sealNo, null);
    }
}
