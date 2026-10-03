package com.example.starter.domain;

import java.time.Instant;

/**
 * 花序：一次开花的授粉对象。
 * 授粉窗口为 [firstOpenAt, firstOpenAt + 24h)，左闭右开，统一 UTC。
 *
 * @param id 主键
 * @param code 花序业务编号，全局唯一
 * @param firstOpenAt 首次开放时刻（UTC），24 小时授粉窗口起点
 * @param removedAt 摘除时刻（UTC），null 表示未摘除；摘除后禁止新授粉操作但保留历史
 * @param createdAt 记录创建时刻（UTC）
 */
public record Inflorescence(long id, String code, Instant firstOpenAt, Instant removedAt, Instant createdAt) {
}