package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 单个检验预约创建请求。仅证物当前保管人可为在库（SEALED）且封签正常的证物创建预约；
 * 时段为 UTC 左闭右开 [startAt, endAt)，开始时刻不得早于服务端当前时刻。
 *
 * @param commandKey     幂等命令键
 * @param reservationKey 预约业务键，全局唯一
 * @param benchKey       检验台业务键
 * @param examinerId     检验执行人（开始/完成须由此人操作）
 * @param startAt        预约开始时刻（UTC，含）
 * @param endAt          预约结束时刻（UTC，不含）
 */
public record ReservationCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String reservationKey,
        @NotBlank @Size(max = 64) String benchKey,
        @NotBlank @Size(max = 64) String examinerId,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt) {
}
