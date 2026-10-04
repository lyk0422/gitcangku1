package com.example.starter.observation;

import java.time.Instant;

/**
 * 披露附页记录：对应 disclosure_appendix 表的一行；首次发布后的新更正只追加附页。
 *
 * @param observationId 观测记录唯一标识
 * @param seq           附页序号，从 1 开始单调递增
 * @param version       本次更正产生的观测版本号
 * @param operation     产生附页的操作类型：MERGE / DELETE / RESOLVE
 * @param requestId     产生附页的请求标识
 * @param recordedAtUtc 附页登记时刻（UTC）
 */
public record AppendixRecord(
        String observationId,
        int seq,
        int version,
        String operation,
        String requestId,
        Instant recordedAtUtc) {
}