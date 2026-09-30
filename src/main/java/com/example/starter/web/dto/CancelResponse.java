package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 撤销结果。
 *
 * @param cancelled 被撤销的申请
 * @param promoted 接替成功的候补；无合格候补为 null
 * @param gapStartAt 释放空档开始（UTC，左闭）；候补撤销无空档为 null
 * @param gapEndAt 释放空档结束（UTC，右开）；候补撤销无空档为 null
 */
public record CancelResponse(
        RequestView cancelled,
        RequestView promoted,
        Instant gapStartAt,
        Instant gapEndAt) {
}
