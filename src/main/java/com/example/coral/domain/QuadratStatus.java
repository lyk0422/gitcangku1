package com.example.coral.domain;

/**
 * 样方状态：OPEN开放（可登记移植与复核） / SEALED已封存（只读，清单已固化）。
 */
public enum QuadratStatus {
    OPEN,
    SEALED
}