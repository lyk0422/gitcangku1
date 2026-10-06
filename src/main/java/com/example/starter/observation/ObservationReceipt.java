package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 源输入收件记录：对应 observation_receipt 表的一行。
 *
 * <p>每次源输入成功收件（创建/合并/冲突解决）都追加一行，另存原值、原始单位与精确规范值；
 * 即使规范读数与当前等价、业务版本不前进，也必须留下收件溯源。失败请求回滚，不留收件记录。
 *
 * @param receiptId       收件记录自增标识；插入前为 null
 * @param observationId   观测记录唯一标识
 * @param requestId       产生该收件的请求标识（与 request_log 对应，全局唯一）
 * @param operation       收件来源操作：CREATE / MERGE / RESOLVE
 * @param dimension       量纲：LENGTH / TEMPERATURE；旧无单位读数为 null
 * @param originalValue   源输入原始读数（十进制原文，最多三位小数）
 * @param originalUnit    源输入原始单位（m/cm/C/K）；旧无单位读数为 null
 * @param normalizedValue 精确规范读数：长度统一为 m，温度统一为 C，不舍入
 * @param resultVersion   收件确认后观测记录所处版本号（等价不增版时为当前版本）
 * @param receivedAtUtc   收件时刻（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationReceipt(
        Long receiptId,
        String observationId,
        String requestId,
        String operation,
        String dimension,
        String originalValue,
        String originalUnit,
        String normalizedValue,
        int resultVersion,
        Instant receivedAtUtc) {
}
