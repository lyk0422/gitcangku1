package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 联程行李装载交接 API 的请求与响应 DTO。
 */
public final class BaggageDtos {

    private BaggageDtos() {
    }

    /** 登记航段请求。capacity 为封舱前清单容量上限，null 表示不限容量，装载按当前容量校验。 */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination,
            @PositiveOrZero(message = "容量必须为非负整数") Integer capacity) {

        /** 不指定容量（不限容量）的便捷构造器，兼容既有调用。 */
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

    /** 航段响应。capacity 为容量上限（null 表示不限），loadedCount 为当前 OPEN 清单实际件数。 */
    public record LegResponse(String legId, String origin, String destination,
                              String status, int version, Integer capacity, int loadedCount) {
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

    /** 批量装载响应。 */
    public record LoadResponse(String legId, String status, int version, List<String> loaded) {
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

    /** 认领冻结登记请求：仅未到达最终目的地的在途行李可冻结。 */
    public record ClaimHoldRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "claimKey 不能为空") String claimKey,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "passengerVerificationSummary 不能为空") String passengerVerificationSummary,
            @NotBlank(message = "reason 不能为空") String reason,
            @NotBlank(message = "holdAgentId 不能为空") String holdAgentId,
            @NotBlank(message = "holdAgentRole 不能为空") String holdAgentRole) {
    }

    /** 复核请求：须由与登记角色不同的客服提交，乘客核验摘要须逐字一致。 */
    public record ClaimReviewRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "claimKey 不能为空") String claimKey,
            @NotBlank(message = "passengerVerificationSummary 不能为空") String passengerVerificationSummary,
            @NotBlank(message = "reviewerId 不能为空") String reviewerId,
            @NotBlank(message = "reviewerRole 不能为空") String reviewerRole) {
    }

    /** 二次确认解除请求：摘要再次一致时原子恢复为冻结前可交接状态。 */
    public record ClaimReleaseRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "claimKey 不能为空") String claimKey,
            @NotBlank(message = "passengerVerificationSummary 不能为空") String passengerVerificationSummary,
            @NotBlank(message = "releaseAgentId 不能为空") String releaseAgentId,
            @NotBlank(message = "releaseAgentRole 不能为空") String releaseAgentRole) {
    }

    /** 冻结操作响应：返回实际状态、航段与时刻；removedFromLeg 为移出的 OPEN 航段，未在清单中为 null。 */
    public record ClaimHoldResponse(String claimKey, String bagTag, String status,
                                    String statusBeforeHold, String removedFromLeg,
                                    String reason, String holdAgentId, String holdAgentRole,
                                    String holdAt) {
    }

    /** 复核响应：仅状态推进到 REVIEWED，不改变行李状态；holdDurationSeconds 为已冻结时长（实际值，UTC 区间左闭右开）。 */
    public record ClaimReviewResponse(String claimKey, String bagTag, String status,
                                      String reviewerId, String reviewerRole,
                                      String holdAt, String reviewedAt,
                                      long holdDurationSeconds) {
    }

    /** 解除响应：status 恢复为冻结前状态；restoredTo 为恢复后的可交接行李状态。 */
    public record ClaimReleaseResponse(String claimKey, String bagTag, String status,
                                       String restoredTo, String reviewerId, String reviewerRole,
                                       String holdAt, String releasedAt,
                                       long holdDurationSeconds) {
    }

    /** 不可变证据链事件：字段、排序与 null 含义长期稳定，后续操作只追加不覆盖。 */
    public record ClaimChainEvent(int seq, String eventType, String bagTag, String legId,
                                  String reason, String holdAgentId, String holdAgentRole,
                                  String reviewAgentId, String reviewAgentRole,
                                  String holdAt, String reviewedAt, String releasedAt,
                                  String eventTime) {
    }

    /** 冻结明细：读取不改变任何状态。 */
    public record ClaimDetailResponse(String claimKey, String bagTag, String status,
                                      String statusBeforeHold, String frozenLegId,
                                      String passengerVerificationSummary, String reason,
                                      String holdAgentId, String holdAgentRole,
                                      String reviewerId, String reviewerRole,
                                      String holdAt, String reviewedAt, String releasedAt,
                                      List<ClaimChainEvent> chain) {
    }

    /** 诊断项：一次裁决尝试的实际结果与原因定位。 */
    public record ClaimDiagnosticItem(String operation, String requestId, boolean applied,
                                      int httpStatus, String reason) {
    }

    /** 冻结诊断响应：按提交顺序返回各操作裁决结果及行李当前状态，纯只读。 */
    public record ClaimDiagnosticResponse(String bagTag, String bagStatus,
                                          String activeClaimKey, List<ClaimDiagnosticItem> attempts) {
    }

    /** 误交付差异登记请求：冻结解除后发生的错误交付只登记差异，不回滚、不删除认领记录。 */
    public record DeliveryDifferenceRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "claimKey 不能为空") String claimKey,
            @NotBlank(message = "deliveredToStation 不能为空") String deliveredToStation,
            @NotBlank(message = "expectedStation 不能为空") String expectedStation,
            @NotBlank(message = "registeredBy 不能为空") String registeredBy,
            @NotBlank(message = "note 不能为空") String note) {
    }

    /** 误交付差异登记响应：返回差异登记序号与 UTC 时刻。 */
    public record DeliveryDifferenceResponse(long differenceId, String claimKey, String bagTag,
                                             String deliveredToStation, String expectedStation,
                                             String registeredBy, String registeredAt) {
    }
}
