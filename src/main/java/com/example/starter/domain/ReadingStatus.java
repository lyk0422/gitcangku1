package com.example.starter.domain;

/**
 * 读数状态：VALID=有效（可作为终测候选），VOID=已作废（保留记录但不参与终测判定）。
 */
public enum ReadingStatus {
    VALID,
    VOID
}