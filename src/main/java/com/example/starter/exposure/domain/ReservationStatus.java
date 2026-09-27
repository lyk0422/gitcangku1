package com.example.starter.exposure.domain;

/**
 * 曝光预占状态。
 * <ul>
 *     <li>RESERVED：已预占，占用公告与访客当天两级额度；</li>
 *     <li>CONFIRMED：已被同步确认，持续占用当天额度；</li>
 *     <li>ATTRIBUTED：窗口内曝光确认回执计入归因，结算额度（持续占用当天额度）；</li>
 *     <li>CANCELLED：已取消，两级额度已释放；</li>
 *     <li>EXPIRED：已过期（当前时刻达到到期时刻），两级额度已释放。</li>
 * </ul>
 */
public enum ReservationStatus {
    RESERVED,
    CONFIRMED,
    ATTRIBUTED,
    CANCELLED,
    EXPIRED
}
