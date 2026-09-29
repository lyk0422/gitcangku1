package com.example.starter.batch;

/**
 * 产率平衡核算门禁状态。
 * NONE 未提交核算；WITHIN_LIMIT 最新核算偏差不超上限，门禁通过；
 * EXCEEDED_PENDING 最新核算偏差超限，待两名不同处置人确认，禁止放行；
 * DISPOSED 超限版本已经两名不同处置人确认同一版本，门禁解除。
 * 门禁只认批次最新核算版本；新版本提交后旧版本的处置确认立即失效。
 */
public enum YieldGateStatus {
    NONE,
    WITHIN_LIMIT,
    EXCEEDED_PENDING,
    DISPOSED
}
