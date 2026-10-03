package com.example.starter.domain;

/**
 * 电芯状态：ACTIVE=在批参与交接，ISOLATED=已隔离（保留历史，不再参与交接清单）。
 */
public enum CellStatus {
    ACTIVE,
    ISOLATED
}