package com.example.starter.evidence;

/**
 * 封签版本产生事件。
 * INTAKE 证物入库生成原封签版本；EXAMINATION_COMPLETED 检验完成校验新封签后生成新版本。
 */
public enum SealVersionEvent {
    INTAKE,
    EXAMINATION_COMPLETED
}
