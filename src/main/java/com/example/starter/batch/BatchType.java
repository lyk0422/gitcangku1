package com.example.starter.batch;

/**
 * 批次类型。
 * STANDARD 普通批次：沿用原检验/两角色批准流程，不感知压力曲线（向后兼容）；
 * SEALED 密封产品批次：单点检验通过仍可能持续泄漏，除原必做检验项外还须通过压力曲线检验，
 * 曲线 PASS 后仍需原检验项全部 PASS 与 QUALITY/OPERATIONS 两角色批准。
 */
public enum BatchType {
    STANDARD,
    SEALED
}
