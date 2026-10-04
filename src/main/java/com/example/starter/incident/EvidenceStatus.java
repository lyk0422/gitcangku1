package com.example.starter.incident;

/**
 * 任务验收证据状态机：UPLOADED → CONFIRMED 或 UPLOADED → VOID、CONFIRMED → VOID。
 * VOID 为终态；作废使该证据确认失效，不再计入任务完成的证据门禁。
 * 任务完成（DONE）后证据冻结，禁止任何状态变更。
 */
public enum EvidenceStatus {

    /** 已上传待确认。 */
    UPLOADED,

    /** 已确认（验收人与上传人不同）。 */
    CONFIRMED,

    /** 已作废（终态，确认失效）。 */
    VOID;
}