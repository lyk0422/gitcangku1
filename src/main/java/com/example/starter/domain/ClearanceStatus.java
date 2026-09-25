package com.example.starter.domain;

/**
 * 批件生命周期状态。
 */
public enum ClearanceStatus {
    /** 已批准且尚未起飞：NORMAL 批件在该状态下可被紧急航线抢占。 */
    APPROVED,
    /** 已起飞登记：任何航线都不得抢占该状态的占用。 */
    DEPARTED,
    /** 被紧急航线抢占置换：占用已释放，需重新提交审查，不自动复原。 */
    DISPLACED,
    /** 同一航线重新提交审查并获得新批件后，旧批件失效并释放占用。 */
    SUPERSEDED
}
