package com.example.starter.evidence;

/**
 * 双人重新封存申请状态。
 * PENDING 待见证人确认；CONFIRMED 见证人已确认（终态）；CANCELLED 申请人已撤销（终态）。
 * 决定后不可再变；确认与撤销竞争时仅一个终态成功。
 */
public enum ResealStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}
