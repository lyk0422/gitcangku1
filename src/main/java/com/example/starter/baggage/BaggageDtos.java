package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 联程行李装载交接与认领冻结 API 的请求与响应 DTO。
 */
public final class BaggageDtos {

    private BaggageDtos() {
    }

    /** 登记航段请求；capacity 为容量上限（件），null 表示不限容量。 */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination,
            @Min(value = 0, message = "容量不能为负数") Integer capacity) {

        /** 不限容量的便捷构造器（容量为 null）。 */
        public RegisterLegRequest(String requestId, String legId, String origin, String destination) {
            this(requestId, legId, origin, destination, null);
        }
    }

    /** 登记行李请求：legIds 为 1~5 个有序航段。 */
    public record RegisterBagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "legIds 不能为空")
            @Size(min = 1, max = 5, message = "行程航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> legIds) {
    }

    /** 批量装载请求：1~20 个不重复 bagTag。 */
    public record LoadRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 20, message = "批量装载数量必须为 1~20") List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 封舱请求。 */
    public record SealRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion) {
    }

    /** 到达确认请求：实际袋号集合，须与封舱清单完全一致。 */
    public record ArriveRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "bagTags 不能为空") List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 差异到达请求：实际袋号集合须为封舱清单子集（允许空集），expectedVersion 做并发版本校验。 */
    public record DifferenceArriveRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "bagTags 不能为空") List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 补到请求：短卸行李在缺失航段的应到站实际到达。 */
    public record RecoverRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "missingLegId 不能为空") String missingLegId,
            @NotBlank(message = "actualStation 不能为空") String actualStation) {
    }

    /** 认领冻结登记请求：客服登记 claimKey、乘客核验摘要与冻结原因。 */
    public record HoldClaimRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "claimKey 不能为空") String claimKey,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "passengerDigest 不能为空") String passengerDigest,
            @NotBlank(message = "reason 不能为空") String reason,
            @NotBlank(message = "operatorId 不能为空") String operatorId,
            @NotBlank(message = "operatorRole 不能为空") String operatorRole) {
    }

    /** 认领冻结复核请求：必须由与登记人不同的复核客服提交相同乘客核验摘要。 */
    public record ClaimReviewRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "claimKey 不能为空") String claimKey,
            @NotBlank(message = "passengerDigest 不能为空") String passengerDigest,
            @NotBlank(message = "operatorId 不能为空") String operatorId,
            @NotBlank(message = "operatorRole 不能为空") String operatorRole) {
    }

    /** 认领冻结解除请求：复核人的第二次确认，原子恢复为冻结前可交接状态。 */
    public record ClaimReleaseRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "claimKey 不能为空") String claimKey,
            @NotBlank(message = "operatorId 不能为空") String operatorId,
            @NotBlank(message = "operatorRole 不能为空") String operatorRole) {
    }

    /** 误交付差异登记请求：被冻结行李的后续误交付只登记差异，认领记录不删除。 */
    public record DeliveryDifferenceRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "claimKey 不能为空") String claimKey,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "note 不能为空") String note,
            @NotBlank(message = "operatorId 不能为空") String operatorId,
            @NotBlank(message = "operatorRole 不能为空") String operatorRole) {
    }

    /** 航段响应。 */
    public record LegResponse(String legId, String origin, String destination,
                              String status, int version, Integer capacity) {
    }

    /** 行程明细项。 */
    public record ItineraryItem(int seq, String legId, String origin, String destination) {
    }

    /** 行李轨迹事件。 */
    public record TraceEvent(int seq, String eventType, String legId,
                             String location, String eventTime) {
    }

    /** 行李响应：含完整事件轨迹。 */
    public record BagResponse(String bagTag, String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
                              String shortLegId, String shortDestination, String shortRegisteredAt,
                              List<TraceEvent> events) {
    }

    /**
     * 批量装载响应。
     * capacity 为航段容量上限（null 不限）；loadedCount 为实际在清单件数；
     * remaining 为容量剩余差额（实际容量 - 实际件数），不限容量为 null。
     */
    public record LoadResponse(String legId, String status, int version, List<String> loaded,
                               Integer capacity, int loadedCount, Integer remaining) {
    }

    /** 封舱响应：manifest 为只读装载清单快照。 */
    public record SealResponse(String legId, String status, int version, List<String> manifest) {
    }

    /** 到达确认响应。 */
    public record ArriveResponse(String legId, String status, int version, List<String> arrived) {
    }

    /** 差异到达响应：arrived 为实际到达，shortUnloaded 为清单中缺失并转短卸的袋号。 */
    public record DifferenceArriveResponse(String legId, String status, int version,
                                           List<String> arrived, List<String> shortUnloaded) {
    }

    /** 补到响应。 */
    public record RecoverResponse(String bagTag, String status, String currentLocation,
                                  int nextLegIndex, String recoveredLegId) {
    }

    /** 封舱清单查询响应。 */
    public record ManifestResponse(String legId, String status, int version, List<String> manifest) {
    }

    /** 航段差异快照响应：arrivalType 为 EXACT/DIFF，actual 仅差异到达时有值。 */
    public record LegDifferenceResponse(String legId, String status, int version,
                                        List<String> manifest, String arrivalType, List<String> actual) {
    }

    /** 未补到行李清单项。 */
    public record ShortItem(String bagTag, String missingLegId, String expectedStation,
                            String registeredAt, int nextLegIndex, String currentLocation) {
    }

    /** 未补到清单响应。 */
    public record ShortListResponse(List<ShortItem> shortUnloaded) {
    }

    /** 认领冻结链事件项：链内只追加，seq 与 UTC 时刻稳定，与航段无关时 legId/reason 为 null。 */
    public record ClaimEventItem(int seq, String eventType, String legId, String reason,
                                 String operatorId, String operatorRole, String eventTime) {
    }

    /** 认领冻结明细：固化冻结前后状态、两位操作人与时刻，UTC 左闭右开时长。 */
    public record ClaimHoldResponse(String claimKey, String bagTag, String status,
                                    String priorStatus, String restoredStatus,
                                    String legId, String reason,
                                    String heldBy, String heldByRole, String heldAt,
                                    String reviewedBy, String reviewedByRole, String reviewedAt,
                                    String releasedBy, String releasedAt,
                                    long heldDurationSeconds, int discrepancyCount,
                                    List<ClaimEventItem> events) {
    }

    /** 误交付差异登记响应：返回累计差异实际值。 */
    public record DeliveryDifferenceResponse(String claimKey, String bagTag,
                                             int discrepancyCount, String registeredAt,
                                             List<ClaimEventItem> events) {
    }

    /**
     * 行李诊断只读视图：不改变任何状态。
     * nextLegId 为当前待乘航段（已完成行程为 null）；capacity 为该航段容量上限（null 不限）；
     * loadedCount 为该航段 OPEN 清单实际件数；remaining 为容量差额（要求容量 - 实际件数），不限为 null；
     * activeClaimKey 为生效冻结键，无生效冻结为 null；handlable 表示当前是否处于可交接状态。
     */
    public record BagDiagnosticsResponse(String bagTag, String status, String currentLocation,
                                         int nextLegIndex, String nextLegId, String loadedLegId,
                                         Integer capacity, Integer loadedCount, Integer remaining,
                                         String activeClaimKey, boolean handlable) {
    }
}
