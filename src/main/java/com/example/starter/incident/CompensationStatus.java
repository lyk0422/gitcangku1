package com.example.starter.incident;

/**
 * 单次补偿尝试结果。失败仅保存失败诊断、不改变步骤 APPLIED 状态，
 * 允许用新 commandKey 重试；成功保存补偿证据并将步骤置为 COMPENSATED。
 */
public enum CompensationStatus {

    /** 补偿成功：步骤转 COMPENSATED，evidence 非空。 */
    SUCCEEDED,

    /** 补偿失败：仅写入 failureDiagnostic，步骤保持 APPLIED。 */
    FAILED
}
