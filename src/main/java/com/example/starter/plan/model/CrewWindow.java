package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 乘务员在某张已发布计划上的占用窗口：该计划全部区段占用的最小左端点与最大右端点，
 * 区间左闭右开；仅取状态为 PUBLISHED 的计划（取消计划的窗口立即释放）。
 *
 * @param memberId     乘务员 id
 * @param scheduleKey  占用该乘务员的计划业务键
 * @param startUtc     窗口开始时刻（含），UTC
 * @param endUtc       窗口结束时刻（不含），UTC
 */
public record CrewWindow(long memberId, String scheduleKey, Instant startUtc, Instant endUtc) {
}
