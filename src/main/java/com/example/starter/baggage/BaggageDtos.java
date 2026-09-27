package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.constraints.Min;
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

    /** 配置中转点最大滞留分钟数请求：可重复提交更新同一站点。 */
    public record ConfigureDwellLimitRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") @Size(max = 64, message = "operator 过长") String operator,
            @NotNull(message = "maxDwellMinutes 不能为空")
            @Min(value = 1, message = "maxDwellMinutes 必须为正数") Long maxDwellMinutes) {
    }

    /** 中转点滞留上限配置响应。 */
    public record DwellConfigResponse(String station, long maxDwellMinutes) {
    }

    /** 配置航段装载截止与容量请求：loadDeadline 为 ISO-8601 UTC 时刻。 */
    public record ConfigureLegOpsRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") @Size(max = 64, message = "operator 过长") String operator,
            @NotBlank(message = "loadDeadline 不能为空") String loadDeadline,
            @NotNull(message = "maxCapacity 不能为空")
            @Min(value = 1, message = "maxCapacity 必须为正数") Integer maxCapacity) {
    }

    /** 航段运行配置响应。 */
    public record LegOpsConfigResponse(String legId, String loadDeadline, int maxCapacity) {
    }

    /** 中转到达核对请求：批量登记行李到达中转点，任一行李非法整次回滚。 */
    public record TransitArriveRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") @Size(max = 64, message = "operator 过长") String operator,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 20, message = "批量到达数量必须为 1~20") List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 中转到达明细项：arrivedAt 为到达中转点时刻（UTC）。 */
    public record TransitArriveItem(String bagTag, String arrivedAt) {
    }

    /** 中转到达核对响应。 */
    public record TransitArriveResponse(String station, List<TransitArriveItem> arrived) {
    }

    /** 滞留超时判定请求：对在中转点滞留中的行李按可注入时钟判定。 */
    public record DwellTimeoutCheckRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") @Size(max = 64, message = "operator 过长") String operator,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 20, message = "批量判定数量必须为 1~20") List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /**
     * 超时判定明细项：dwellMinutes 为实际滞留分钟数，maxDwellMinutes 为要求上限，
     * exceedByMinutes 为超出差额（仅超时时非 null）；status 为 OPEN 滞留中或 EXCEEDED 已超时。
     */
    public record DwellCheckItem(String bagTag, String station, String status,
                                 String arrivedAt, long dwellMinutes, Long maxDwellMinutes,
                                 Long exceedByMinutes) {
    }

    /** 超时判定响应。 */
    public record DwellTimeoutCheckResponse(String station, List<DwellCheckItem> items) {
    }

    /** 复核解除请求：必须指定下一航段，解除时重新校验该航段装载截止与容量。 */
    public record ReleaseDwellRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "reviewer 不能为空") @Size(max = 64, message = "reviewer 过长") String reviewer,
            @NotBlank(message = "conclusion 不能为空") @Size(max = 256, message = "conclusion 过长") String conclusion,
            @NotBlank(message = "nextLegId 不能为空") String nextLegId) {
    }

    /** 复核解除响应。 */
    public record ReleaseDwellResponse(String bagTag, String status, String nextLegId,
                                       String releasedAt, String reviewer) {
    }

    /** 改派请求：以 newLegIds 整体替换待乘行程（自当前 nextLegIndex 起），不改写历史证据。 */
    public record RerouteRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") @Size(max = 64, message = "operator 过长") String operator,
            @NotNull(message = "newLegIds 不能为空")
            @Size(min = 1, max = 5, message = "改派航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> newLegIds) {
    }

    /** 改派响应：itinerary 为替换后的完整行程。 */
    public record RerouteResponse(String bagTag, int nextLegIndex, List<ItineraryItem> itinerary) {
    }

    /**
     * 滞留明细响应：arrivedAt/exceededAt/releasedAt/reviewer/reviewConclusion/nextLegId 为不可变证据字段，
     * 未发生对应事件时为 null；closedAt 为离开滞留站点时刻，未离开为 null。
     */
    public record DwellRecordResponse(String bagTag, String station, String status,
                                      String arrivedAt, String exceededAt, String releasedAt,
                                      String reviewer, String reviewConclusion, String nextLegId,
                                      String closedAt) {
    }

    /** 滞留历史事件项：operator/nextLegId/detail 仅对相应事件类型非 null。 */
    public record DwellEventItem(int seq, String eventType, String station, String operator,
                                 String nextLegId, String detail, String eventTime) {
    }

    /** 滞留历史响应：事件按 seq 稳定升序。 */
    public record DwellHistoryResponse(String bagTag, List<DwellEventItem> events) {
    }

    /** 中转点滞留诊断响应：maxDwellMinutes 未配置为 null；各计数为当前状态分布。 */
    public record StationDwellDiagnostics(String station, Long maxDwellMinutes,
                                          int openCount, int exceededCount,
                                          int releasedCount, int closedCount) {
    }
}
