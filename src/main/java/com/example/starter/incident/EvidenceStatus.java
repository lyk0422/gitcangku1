package com.example.starter.incident;

/**
 * 任务验收证据状态机：PENDING 待确认 / CONFIRMED 已确认 / VOIDED 已作废。
 * 允许 PENDING→CONFIRMED、PENDING→VOIDED、CONFIRMED→VOIDED；VOIDED 为终态。
 * 任务完成后全部证据冻结，禁止任何状态变更。
 */
public enum EvidenceStatus {

    /** 已上传待确认。 */
    PENDING,

    /** 已确认（验收人与上传人不同）；任务完成门禁只统计该状态。 */
    CONFIRMED,

    /** 已作废（终态），作废使该证据确认失效，不再计入完成门禁。 */
    VOIDED;
}
