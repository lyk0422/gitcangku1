package com.example.starter.batch;

/**
 * MAJOR 储运偏差裁决结论：
 * REWORK 返工：原批次置 REWORKED，沿既有返工链产生返工子批；
 * REJECT 拒收：本批次置 DISPOSED，其全部后代按召回口径拦截。
 * MINOR 偏差不走裁决，由质控确认（CONFIRM）解除门禁。
 */
public enum ExcursionDecision {
    REWORK,
    REJECT
}
