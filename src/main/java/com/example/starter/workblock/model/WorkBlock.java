package com.example.starter.workblock.model;

import java.time.Instant;

/**
 * 铁路施工占用窗口主记录。窗口为 UTC 左闭右开区间 [startUtc, endUtc)。
 *
 * @param id          主键
 * @param workKey     施工单业务键，全局唯一
 * @param version     施工单版本，创建为 1，每次修改成功加一，取消不改版本
 * @param status      施工单状态
 * @param startUtc    窗口开始时刻（含），UTC
 * @param endUtc      窗口结束时刻（不含），UTC，晚于 startUtc
 * @param operator    操作者标识，参与幂等指纹
 * @param createdAt   创建时刻，UTC 毫秒
 * @param updatedAt   最近变更时刻，UTC 毫秒
 * @param cancelledAt 取消时刻，UTC 毫秒；{@code null} 表示未取消
 */
public record WorkBlock(long id, String workKey, int version, WorkBlockStatus status,
                        Instant startUtc, Instant endUtc, String operator,
                        long createdAt, long updatedAt, Long cancelledAt) {
}
