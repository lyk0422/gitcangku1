package com.example.starter.domain;

/** 电芯状态：AVAILABLE=可入批，IN_BATCH=在批，ISOLATED=已隔离，HANDED_OVER=已交接。 */
public enum CellStatus {
    AVAILABLE,
    IN_BATCH,
    ISOLATED,
    HANDED_OVER
}