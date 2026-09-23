package com.example.starter.evidence;

/**
 * 组合借出包状态。
 * PARTIAL 未全部归还（含刚创建未归还任何一件，以及分批归还过程中）；
 * CLOSED 全部归还（终态）：最后一批归还事务内自动关闭，不提供单独关闭入口；
 * CANCELLED 借出撤销（终态）：尚无任何归还且全部证物仍在借出人名下时由经办人原子撤销。
 */
public enum PackageStatus {
    PARTIAL,
    CLOSED,
    CANCELLED
}
