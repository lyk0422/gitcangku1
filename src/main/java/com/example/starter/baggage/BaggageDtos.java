package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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

    /** 中转点滞留配置请求：maxDwellMinutes 为正整数分钟。 */
    public record DwellConfigRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "station 不能为空") String station,
            @NotNull(message = "maxDwellMinutes 不能为空")
            @Positive(message = "maxDwellMinutes 必须为正整数") Integer maxDwellMinutes) {
    }

    /** 批量到达核对（超时判定）请求：1~20 个不重复 bagTag。 */
    public record DwellCheckRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 20, message = "批量核对数量必须为 1~20") List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 滞留解除请求：必须登记复核人、复核结论并指定行程内下一航段。 */
    public record DwellReleaseRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "reviewer 不能为空") String reviewer,
            @NotBlank(message = "reviewNote 不能为空") String reviewNote,
            @NotBlank(message = "nextLegId 不能为空") String nextLegId) {
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

    /** 行李响应：含完整事件轨迹与当前滞留信息（未在中转点等待时 dwell 字段为 null）。 */
    public record BagResponse(String bagTag, String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
                              String shortLegId, String shortDestination, String shortRegisteredAt,
                              String dwellStation, String dwellArrivedAt,
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

    /** 中转点滞留配置响应。 */
    public record DwellConfigResponse(String station, int maxDwellMinutes) {
    }

    /** 单件行李核对结果：dwellMinutes 为实际滞留分钟数，maxDwellMinutes 为要求上限。 */
    public record DwellCheckItem(String bagTag, long dwellMinutes, int maxDwellMinutes, boolean exceeded) {
    }

    /** 批量到达核对响应：results 按 bagTag 排序，checkedAt 为判定时刻（UTC）。 */
    public record DwellCheckResponse(String station, int maxDwellMinutes, String checkedAt,
                                     List<DwellCheckItem> results) {
    }

    /** 滞留解除响应。 */
    public record DwellReleaseResponse(String bagTag, String status, String nextLegId,
                                       String reviewer, String releasedAt) {
    }

    /**
     * 滞留证据记录：arrivedAt/dwellMinutes/maxDwellMinutes 仅到达与超时记录有值，
     * nextLegId/reviewer/reviewNote 仅解除记录有值，其余为 null；时间均为 UTC。
     */
    public record DwellRecordItem(long id, String recordType, String station, String arrivedAt,
                                  String recordedAt, Long dwellMinutes, Integer maxDwellMinutes,
                                  String nextLegId, String reviewer, String reviewNote) {
    }

    /** 行李滞留证据历史响应：records 按 id 升序（提交顺序），只读不改状态。 */
    public record DwellRecordListResponse(String bagTag, List<DwellRecordItem> records) {
    }

    /** 行李当前滞留诊断：未在中转点等待时 station/arrivedAt/dwellMinutes/maxDwellMinutes 为 null。 */
    public record DwellStatusResponse(String bagTag, String status, String station, String arrivedAt,
                                      Long dwellMinutes, Integer maxDwellMinutes) {
    }

    /** 当前滞留超时行李明细项。 */
    public record DwellExceededItem(String bagTag, String station, String arrivedAt) {
    }

    /** 中转点当前滞留超时清单响应：按到达时刻、袋号稳定排序。 */
    public record DwellExceededListResponse(String station, List<DwellExceededItem> exceeded) {
    }
}
