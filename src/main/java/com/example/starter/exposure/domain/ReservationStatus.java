package com.example.starter.exposure.domain;

/**
 * 曝光预占状态。
 * <ul>
 *     <li>RESERVED：已预占，占用公告与访客当天两级额度；</li>
 *     <li>SETTLING：已被版本撤回快照冻结，等待回执或显式结算决议，期间禁止普通确认/取消与新预占；</li>
 *     <li>CONFIRMED：已确认（普通确认或快照内合法回执），持续占用当天额度；</li>
 *     <li>REJECTED：快照项驳回终态（非法回执／到期／结算判定无法合法确认），两级额度已释放；</li>
 *     <li>CANCELLED：已取消，两级额度已释放；</li>
 *     <li>EXPIRED：已过期（当前时刻达到到期时刻），两级额度已释放。</li>
 * </ul>
 */
public enum ReservationStatus {
    RESERVED,
    SETTLING,
    CONFIRMED,
    REJECTED,
    CANCELLED,
    EXPIRED
}
