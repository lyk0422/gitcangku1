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

    /**
     * 登记航段请求。
     * capacity 为可选装载容量（正整数，null 不限）；loadCutoff 为可选截载时刻（UTC，ISO-8601，
     * null 不限），区间左闭右开。
     */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination,
            @jakarta.validation.constraints.Positive(message = "容量必须为正整数") Integer capacity,
            String loadCutoff) {

        /** 兼容无容量、无截载时刻的航段登记：两者均不限。 */
        public RegisterLegRequest(String requestId, String legId, String origin, String destination) {
            this(requestId, legId, origin, destination, null, null);
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

    /** 航段响应：capacity/loadCutoff 为可选容量与截载时刻（UTC），未配置为 null。 */
    public record LegResponse(String legId, String origin, String destination,
                              String status, int version, Integer capacity, String loadCutoff) {
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

    // ===== 滞留监控 DTO =====

    /** 中转点滞留配置请求：station 唯一，maxDwellMinutes 为正整数。 */
    public record RegisterTransitPointRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotBlank(message = "station 不能为空") String station,
            @jakarta.validation.constraints.Positive(message = "最大滞留分钟数必须为正整数")
            @NotNull(message = "maxDwellMinutes 不能为空") Integer maxDwellMinutes) {
    }

    /** 中转点配置响应。 */
    public record TransitPointResponse(String station, int maxDwellMinutes) {
    }

    /**
     * 批量中转到达核对请求：1~20 个不重复到达项，每项指定行李与其到达中转点。
     * 任一行李状态或到达点非法则整次回滚。
     */
    public record TransitArrivalRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "arrivals 不能为空")
            @Size(min = 1, max = 20, message = "批量到达数量必须为 1~20")
            List<@jakarta.validation.Valid ArrivalItem> arrivals) {
    }

    /** 单个到达核对项。 */
    public record ArrivalItem(
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "station 不能为空") String station) {
    }

    /**
     * 批量超时判定请求：按注入时钟对仍在等待（ACTIVE）的行李判定。
     * bagTags 为空表示判定指定中转点全部等待中的行李。
     */
    public record DwellCheckRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            String station,
            List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 复核解除阻断请求：必须指定下一航段、处理人与复核结论，解除时重新校验截止与容量。 */
    public record DwellReleaseRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "nextLegId 不能为空") String nextLegId,
            @NotBlank(message = "reviewConclusion 不能为空") String reviewConclusion) {
    }

    /** 滞留访问当前明细（诊断/明细查询项）。 */
    public record DwellItem(String bagTag, String station, String state,
                           String arrivedAt, int limitMinutes, String deadline,
                           String exceededAt, Long elapsedMinutes, Long remainingMinutes,
                           Long excessMinutes) {
    }

    /** 批量到达核对响应。 */
    public record TransitArrivalResponse(List<String> arrived, int count) {
    }

    /** 批量超时判定响应：checked 为实际参与判定数，exceeded 为转 DWELL_EXCEEDED 的袋号。 */
    public record DwellCheckResponse(String station, int checked, List<String> exceeded,
                                    int exceededCount) {
    }

    /** 解除阻断响应：含实际滞留、要求上限与差额（分钟）。 */
    public record DwellReleaseResponse(String bagTag, String status, String station,
                                      String nextLegId, String reviewer, String releasedAt,
                                      long elapsedMinutes, int limitMinutes, long excessMinutes) {
    }

    /** 滞留明细列表响应：当前滞留访问（ACTIVE/EXCEEDED/RELEASED）。 */
    public record DwellListResponse(String station, List<DwellItem> items) {
    }

    /** 单行李滞留历史证据项：只追加、不改写，字段与排序稳定。 */
    public record DwellHistoryItem(int seq, String eventType, String station,
                                   String arrivedAt, Integer limitMinutes, String deadline,
                                   String eventTime, Long elapsedMinutes, Long excessMinutes,
                                   String nextLegId, String reviewer, String reviewConclusion) {
    }

    /** 滞留历史响应：按 seq 升序。 */
    public record DwellHistoryResponse(String bagTag, List<DwellHistoryItem> events) {
    }
}
