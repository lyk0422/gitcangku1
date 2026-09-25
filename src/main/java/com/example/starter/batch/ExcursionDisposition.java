package com.example.starter.batch;

/**
 * 储运偏差裁决结论。
 * MAJOR 偏差只能裁决为 REWORK（沿返工链产生新返工批）或 REJECT（批次及其后代按召回口径拦截）；
 * MINOR 偏差只能由质控 CONFIRM 确认，确认后不再阻断放行。
 */
public enum ExcursionDisposition {
    REWORK,
    REJECT,
    CONFIRM
}
