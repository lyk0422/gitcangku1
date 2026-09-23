package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 区段封锁切换单。调度员登记区段与左闭右开 UTC 封锁窗口，
 * 登记后为 REGISTERED（可预览、可提交激活），激活成功后为 ACTIVE（终态，不可再次激活）。
 *
 * @param id          主键
 * @param switchKey   切换单业务键，全局唯一，登记后不可复用
 * @param sectionId   封锁区段 ID
 * @param startUtc    封锁窗口开始时刻（含），UTC
 * @param endUtc      封锁窗口结束时刻（不含），UTC，必须晚于 startUtc
 * @param status      切换单状态：REGISTERED 已登记 / ACTIVE 已激活
 * @param snapshotJson 激活成功后的完整切换快照（JSON）；登记态为 null，激活后不可变
 * @param createdAt   创建时刻，UTC 毫秒
 * @param updatedAt   最近变更时刻，UTC 毫秒
 */
public record SectionSwitch(long id, String switchKey, String sectionId, Instant startUtc,
                            Instant endUtc, SwitchStatus status, String snapshotJson,
                            long createdAt, long updatedAt) {
}
