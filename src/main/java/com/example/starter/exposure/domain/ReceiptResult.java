package com.example.starter.exposure.domain;

/**
 * 归因回执裁决结果（不可变历史记录的结果字段）。
 * <ul>
 *     <li>ATTRIBUTED：回执业务时刻落在 [预占时刻, 预占时刻+窗口) 内且预占仍为 RESERVED，
 *     计入归因，预占结算为 ATTRIBUTED，额度持续占用，每个预占仅可能出现一次；</li>
 *     <li>LATE_RECEIPT：窗口外（早于预占时刻或不早于窗口终点）或预占已 CANCELLED/EXPIRED，
 *     仅记录回执，不得二次确认或释放配额；</li>
 *     <li>ALREADY_ATTRIBUTED：预占已被更早提交的回执归因，本回执不重复结算；</li>
 *     <li>ALREADY_CONFIRMED：预占已被同步确认，本回执不重复结算。</li>
 * </ul>
 */
public enum ReceiptResult {
    ATTRIBUTED,
    LATE_RECEIPT,
    ALREADY_ATTRIBUTED,
    ALREADY_CONFIRMED
}
