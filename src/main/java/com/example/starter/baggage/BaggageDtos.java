package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 联程行李装载交接 API 的请求与响应 DTO。
 */
public final class BaggageDtos {

    private BaggageDtos() {
    }

    /** 登记航段请求。 */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination) {
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

    /** 航段响应。 */
    public record LegResponse(String legId, String origin, String destination,
                              String status, int version) {
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

    /** 认领冻结登记请求：claimKey 全局唯一，verifyDigest 为乘客核验摘要，operator 为登记客服。 */
    public record ClaimHoldRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "claimKey 不能为空") String claimKey,
            @NotBlank(message = "verifyDigest 不能为空") String verifyDigest,
            @NotBlank(message = "reason 不能为空") String reason,
            @NotBlank(message = "operator 不能为空") String operator) {
    }

    /** 冻结复核请求：须由与登记人不同的客服提交，verifyDigest 须与登记摘要一致。 */
    public record ClaimReviewRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "verifyDigest 不能为空") String verifyDigest,
            @NotBlank(message = "operator 不能为空") String operator) {
    }

    /** 冻结解除确认请求：须由复核客服本人提交。 */
    public record ClaimReleaseRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator) {
    }

    /** 冻结登记响应：removedLegId 为同一事务内移出的 OPEN 航段，未装载为 null。 */
    public record ClaimHoldResponse(String claimKey, String bagTag, String holdStatus, String bagStatus,
                                    String priorStatus, String removedLegId, String reason,
                                    String heldBy, String heldAt) {
    }

    /** 冻结复核响应。 */
    public record ClaimReviewResponse(String claimKey, String bagTag, String holdStatus,
                                      String reviewedBy, String reviewedAt) {
    }

    /** 冻结解除确认响应：bagStatus 为恢复后的可交接状态。 */
    public record ClaimReleaseResponse(String claimKey, String bagTag, String holdStatus, String bagStatus,
                                       String releasedBy, String releasedAt) {
    }

    /** 冻结单明细：未复核/未解除的字段为 null。 */
    public record ClaimHoldDetail(String claimKey, String bagTag, String holdStatus,
                                  String priorStatus, String removedLegId, String reason,
                                  String heldBy, String heldAt,
                                  String reviewedBy, String reviewedAt,
                                  String releasedBy, String releasedAt) {
    }

    /** 冻结不可变链记录项：eventTime 为 UTC ISO 串，legId/reason 视类型为 null。 */
    public record ClaimHoldEventItem(int seq, String eventType, String claimKey, String bagTag,
                                     String legId, String reason, String actor, String eventTime) {
    }

    /** 行李冻结链历史响应：按链记录插入顺序（id 升序）稳定排序。 */
    public record ClaimHoldHistoryResponse(String bagTag, List<ClaimHoldEventItem> events) {
    }

    /** 冻结单诊断清单响应。 */
    public record ClaimHoldListResponse(List<ClaimHoldDetail> holds) {
    }
}
