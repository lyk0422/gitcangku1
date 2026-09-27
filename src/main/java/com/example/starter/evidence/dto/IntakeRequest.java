package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证物入库请求。保管人取自 X-Actor-Id，初始状态 SEALED、封签版本 1。
 *
 * @param commandKey  幂等命令键
 * @param evidenceKey 证物业务键，全局唯一
 * @param caseKey     所属案件键
 * @param category    证物类别
 * @param sealNo      封条编号
 * @param location    原存放位置（库位），可空；非空时入库后不可修改并随跨案移交快照固化
 */
public record IntakeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String evidenceKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotBlank @Size(max = 64) String category,
        @NotBlank @Size(max = 64) String sealNo,
        @Size(max = 64) String location) {
}
