package com.example.starter.domain;

/**
 * 标签当前状态。
 * DETACHED：已摘除，可发起交接或由当前持有站点再次附着；
 * ATTACHED：附着中，摘除前禁止交接与再次附着。
 */
public enum TagStatus {
    DETACHED,
    ATTACHED
}