package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** API 请求与响应模型。 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 接收登记请求：批号、容量 mL、供应商到期 UTC 时刻。 */
    public record ReceiveRequest(@NotBlank String lotNumber, @NotNull BigDecimal capacityMl,
            @NotNull Instant supplierExpiryUtc) {}

    /** 领用请求：幂等键与领用量 mL。 */
    public record DispenseRequest(@NotBlank String requestId, @NotNull BigDecimal amountMl) {}

    /** 批号召回请求：隔离原因。 */
    public record RecallRequest(@NotBlank String reason) {}

    /** 试剂瓶视图。 */
    public record BottleView(long id, String lotNumber, BigDecimal capacityMl, BigDecimal remainingMl,
            Instant supplierExpiryUtc, Instant openedAtUtc, Instant effectiveDeadlineUtc,
            String deadlineSource, boolean quarantined, String quarantineReason) {}

    /** 领用流水视图。 */
    public record DispenseView(long id, long bottleId, String requestId, BigDecimal amountMl,
            BigDecimal remainingAfterMl, String deadlineSource, Instant dispensedAtUtc) {}

    /** 召回结果视图。 */
    public record RecallView(String lotNumber, int quarantinedCount, List<Long> quarantinedBottleIds) {}

    /** 按瓶查询详情：到期来源、剩余量、领用流水与隔离原因。 */
    public record BottleDetailView(BottleView bottle, List<DispenseView> dispenses) {}

    /** 错误响应。 */
    public record ErrorView(String error) {}
}