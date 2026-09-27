package com.example.starter.evidence.dto;

/**
 * 单件证物移交诊断项（实际状态 + 可移交判定与阻断原因）。
 *
 * @param evidenceKey   证物业务键
 * @param exists        证物是否存在
 * @param currentCase   证物当前所属案件；不存在时为 null
 * @param custodianId   当前保管人；不存在时为 null
 * @param status        当前证物状态；不存在时为 null
 * @param sealVersion   当前封签版本
 * @param transferable  是否满足跨案移交前置条件
 * @param reason        阻断原因；null 表示可移交
 */
public record TransferItemDiagnosisView(
        String evidenceKey,
        boolean exists,
        String currentCase,
        String custodianId,
        String status,
        int sealVersion,
        boolean transferable,
        String reason) {
}
