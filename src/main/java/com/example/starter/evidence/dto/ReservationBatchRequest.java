package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 批量检验预约请求。批量先校验全部台位与证物（含批次内相互重叠与同证物重复），
 * 任一失败整批拒绝，不留下任何占用。items 视为无序集合：重放指纹与处理顺序
 * 按 (evidenceKey, benchKey, reservationKey, startAt, endAt) 稳定排序后规范化。
 *
 * @param commandKey 幂等命令键
 * @param items      批量预约明细，非空且不超过 {@value #MAX_ITEMS} 条
 */
public record ReservationBatchRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotEmpty @Valid @Size(max = ReservationBatchRequest.MAX_ITEMS) List<Item> items) {

    /**
     * 单批最多预约条数。
     */
    public static final int MAX_ITEMS = 50;

    /**
     * 返回按稳定业务键排序后的明细，保证幂等指纹规范化，不依赖调用方给出的数组顺序。
     */
    @Override
    public List<Item> items() {
        return items == null ? null : items.stream().sorted(Item.COMPARATOR).toList();
    }

    /**
     * 批量预约中的一条明细。
     *
     * @param reservationKey 预约业务键，全局唯一
     * @param evidenceKey    被检验证物业务键
     * @param benchKey       检验台业务键
     * @param examinerId     检验执行人
     * @param startAt        预约开始时刻（UTC，含）
     * @param endAt          预约结束时刻（UTC，不含）
     */
    public record Item(
            @NotBlank @Size(max = 64) String reservationKey,
            @NotBlank @Size(max = 64) String evidenceKey,
            @NotBlank @Size(max = 64) String benchKey,
            @NotBlank @Size(max = 64) String examinerId,
            @NotNull LocalDateTime startAt,
            @NotNull LocalDateTime endAt) {

        static final Comparator<Item> COMPARATOR = Comparator
                .comparing(Item::evidenceKey)
                .thenComparing(Item::benchKey)
                .thenComparing(Item::reservationKey)
                .thenComparing(Item::startAt)
                .thenComparing(Item::endAt);
    }
}
