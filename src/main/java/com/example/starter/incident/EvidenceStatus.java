package com.example.starter.incident;

/**
 * 任务验收证据状态机：PENDING 待确认，可确认为 CONFIRMED；PENDING/CONFIRMED 可作废为 VOIDED。
 * VOIDED 为终态，作废使该证据确认失效；任务完成后全部证据冻结，禁止再变更。
 */
public enum EvidenceStatus {

    /** 待确认（已上传，尚未被验收人确认）。 */
    PENDING,

    /** 已确认（验收人已确认，确认人与上传人必须不同）。 */
    CONFIRMED,

    /** 已作废（终态，确认失效，不计入完成门禁）。 */
    VOIDED;
}