package com.example.starter.exposure.domain;

/**
 * 曝光确认回执的归因裁决结果。
 * <ul>
 *     <li>ATTRIBUTED：回执在预占时刻后的归因窗口内到达（左闭右开），计入归因并结算额度；</li>
 *     <li>LATE_RECEIPT：回执在归因窗口外到达，仅记录，不确认预占也不释放配额。</li>
 * </ul>
 */
public enum ReceiptResult {
    ATTRIBUTED,
    LATE_RECEIPT
}
