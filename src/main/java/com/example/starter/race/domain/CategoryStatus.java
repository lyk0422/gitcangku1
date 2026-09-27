package com.example.starter.race.domain;

/**
 * 组别状态：OPEN-开放（可登记、可作为重分目标）；SEALED-已封榜，只读，
 * 不再接受登记与重分迁入迁出，历史快照不追溯重写。
 */
public enum CategoryStatus {
    OPEN,
    SEALED
}
