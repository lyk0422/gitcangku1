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

    /**
     * 差异到达请求：仅 SEALED 航段可提交；bagTags 不得重复且必须是封舱清单子集（允许空集合），
     * expectedVersion 为提交时的航段版本。
     */
    public record DiscrepancyArriveRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "actualBagTags 不能为空")
            List<@NotBlank(message = "bagTag 不能为空") String> actualBagTags) {
    }

    /**
     * 补到请求：bagTag 为短卸行李，missingLegId 为其缺失航段，
     * actualStation 为实际到达站（须等于该航段到达站），requestId 全局幂等。
     */
    public record RecoverRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
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

    /** 行李完整轨迹事件项（append-only，UTC 时间）。 */
    public record TraceEvent(int seq, String eventType, String legId, String station, String eventTime) {
    }

    /** 行李响应：含完整轨迹事件列表。 */
    public record BagResponse(String bagTag, String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
                              List<TraceEvent> trace) {
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

    /**
     * 差异到达响应：arrivalMode 固定 DISCREPANCY；arrived 为实际到达（已推进）袋号，
     * shortUnloaded 为转短卸的缺失袋号，registeredAt 为 UTC 登记时刻。
     */
    public record DiscrepancyArriveResponse(String legId, String status, int version,
                                            String arrivalMode, List<String> arrived,
                                            List<String> shortUnloaded, String registeredAt) {
    }

    /** 补到响应：status 为 RECOVERED 或 DELIVERED，nextLegIndex 为推进后的待乘下标。 */
    public record RecoverResponse(String bagTag, String status, String missingLegId,
                                  String currentLocation, int nextLegIndex, String recoveredAt) {
    }

    /** 封舱清单查询响应。 */
    public record ManifestResponse(String legId, String status, int version, List<String> manifest) {
    }

    /**
     * 航段差异快照查询响应：未差异到达时 arrivalMode/arrived/shortUnloaded/registeredAt 为 null；
     * 精确到达时 arrivalMode=EXACT、shortUnloaded 为空数组；差异到达时为 DISCREPANCY 及对应快照。
     */
    public record DiscrepancySnapshotResponse(String legId, String status, int version,
                                              String arrivalMode, List<String> manifest,
                                              List<String> arrived, List<String> shortUnloaded,
                                              String registeredAt) {
    }

    /** 未补到清单条目：短卸行李、缺失航段、应到站与 UTC 登记时刻。 */
    public record ShortUnloadedItem(String bagTag, String missingLegId,
                                    String expectedDestination, String registeredAt) {
    }
}
