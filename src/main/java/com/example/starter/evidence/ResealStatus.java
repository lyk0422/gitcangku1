package com.example.starter.evidence;

/**
 * 重新封存申请状态。
 * PENDING 待见证人确认；CONFIRMED 已确认（证物恢复 SEALED 并换用新封条）；
 * CANCELLED 已撤销（不换封条）。CONFIRMED/CANCELLED 均为终态，不可再变。
 */
public enum ResealStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}
