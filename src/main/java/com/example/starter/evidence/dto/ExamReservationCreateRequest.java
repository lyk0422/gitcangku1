package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 单条检验预约请求。时段为 UTC 左闭右开区间 [startAt, endAt)；
 * 检验室操作人取自 X-Actor-Id。
 *
 * @param commandKey     幂等命令键
 * @param reservationKey 预约业务键，全局唯一
 * @param evidenceKey    证物业务键
 * @param benchKey       检验台业务键
 * @param startAt        预约时段开始时刻（UTC，左闭，不得早于服务端当前时刻）
 * @param endAt          预约时段结束时刻（UTC，右开，须晚于 startAt）
 */
public record ExamReservationCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String reservationKey,
        @NotBlank @Size(max = 64) String evidenceKey,
        @NotBlank @Size(max = 64) String benchKey,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt) {
}
