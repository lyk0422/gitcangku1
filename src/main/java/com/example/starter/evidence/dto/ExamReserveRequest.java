package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量检验预约请求。检验室可为多件在库且封签正常的证物一次性创建预约；
 * 服务端先校验全部台位容量与全部证物状态，任一失败整批回滚，不留下任何占用。
 *
 * @param commandKey 幂等命令键（requestId）
 * @param items      预约明细集合（至少一条）；集合指纹按证物键、台位键、时段规范化后排序
 */
public record ExamReserveRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotEmpty @Valid List<ExamReservationItemRequest> items) {

    /**
     * 单条预约明细。时段为 UTC 左闭右开 [startAt, endAt)，端点相接不算重叠。
     *
     * @param reservationKey 预约业务键，全局唯一
     * @param evidenceKey    待检验证物业务键，必须在库 SEALED 且封签正常
     * @param benchKey       检验台业务键
     * @param startAt        UTC 时段开始时刻（左闭）
     * @param endAt          UTC 时段结束时刻（右开，必须晚于开始时刻）
     */
    public record ExamReservationItemRequest(
            @NotBlank @Size(max = 64) String reservationKey,
            @NotBlank @Size(max = 64) String evidenceKey,
            @NotBlank @Size(max = 64) String benchKey,
            @NotNull LocalDateTime startAt,
            @NotNull LocalDateTime endAt) {
    }
}
