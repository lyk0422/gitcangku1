package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证物入库请求。保管人取自 X-Actor-Id，初始状态 SEALED。
 * locationCode 可空：为空时进入默认库位 DEFAULT；指定时库位必须存在且启用。
 *
 * @param commandKey   幂等命令键
 * @param evidenceKey  证物业务键，全局唯一
 * @param caseKey      所属案件键
 * @param category     证物类别
 * @param sealNo       封条编号
 * @param locationCode 目标库位编码；null 表示进入默认库位 DEFAULT
 */
public record IntakeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String evidenceKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotBlank @Size(max = 64) String category,
        @NotBlank @Size(max = 64) String sealNo,
        @Size(max = 64) String locationCode) {

    /**
     * 兼容无库位字段的调用：默认进入 DEFAULT 库位。
     */
    public IntakeRequest(String commandKey, String evidenceKey, String caseKey,
                         String category, String sealNo) {
        this(commandKey, evidenceKey, caseKey, category, sealNo, null);
    }
}
