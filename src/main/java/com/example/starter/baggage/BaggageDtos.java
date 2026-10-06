package com.example.starter.baggage;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 联程行李装载交接 API 的请求与响应 DTO。
 * 所有写请求必须携带非空 operator；行李以 bagId 为稳定标识，bagTag 为当前标签并带 tagGeneration 代次。
 */
public final class BaggageDtos {

    private BaggageDtos() {
    }

    /** 登记航段请求。 */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination) {
    }

    /** 登记行李请求：bagId 为稳定标识，bagTag 为初代标签，legIds 为 1~5 个有序航段。 */
    public record RegisterBagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotBlank(message = "bagId 不能为空") String bagId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "legIds 不能为空")
            @Size(min = 1, max = 5, message = "行程航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> legIds) {
    }

    /**
     * 批量装载请求：1~20 个不重复 bagTag；expectedTagGenerations 以 bagTag 为键给出各行李的期望代次，
     * 必须与当前标签号和代次同时匹配，否则 409。
     */
    public record LoadRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 20, message = "批量装载数量必须为 1~20") List<@NotBlank(message = "bagTag 不能为空") String> bagTags,
            @NotNull(message = "expectedTagGenerations 不能为空")
            @Size(min = 1, max = 20, message = "期望代次数量必须为 1~20") Map<@NotBlank String, @NotNull Integer> expectedTagGenerations) {
    }

    /** 封舱请求。 */
    public record SealRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion) {
    }

    /** 到达确认请求：实际袋号集合，须与封舱清单冻结的标签完全一致。 */
    public record ArriveRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "bagTags 不能为空") List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 差异到达请求：实际袋号集合须为封舱清单子集（允许空集），expectedVersion 做并发版本校验。 */
    public record DifferenceArriveRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "bagTags 不能为空") List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 补到请求：短卸行李在缺失航段的应到站实际到达；须同时匹配当前标签代次。 */
    public record RecoverRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "expectedTagGeneration 不能为空") Integer expectedTagGeneration,
            @NotBlank(message = "missingLegId 不能为空") String missingLegId,
            @NotBlank(message = "actualStation 不能为空") String actualStation) {
    }

    /**
     * 换标请求：指定稳定 bagId、期望当前代次 expectedTagGeneration 与全局未使用的新标签 newBagTag；
     * 仅未装舱、非 SHORT_UNLOADED、未 DELIVERED 的行李允许换标，成功后代次加一。
     */
    public record RetagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotBlank(message = "bagId 不能为空") String bagId,
            @NotNull(message = "expectedTagGeneration 不能为空") Integer expectedTagGeneration,
            @NotBlank(message = "newBagTag 不能为空") String newBagTag) {
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

    /** 历史标签别名项。 */
    public record TagAliasItem(String bagTag, int generation) {
    }

    /** 行李响应：稳定 bagId、当前标签/代次、别名与完整事件轨迹。 */
    public record BagResponse(String bagId, String bagTag, int tagGeneration,
                              String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
                              String shortLegId, String shortDestination, String shortRegisteredAt,
                              List<TagAliasItem> aliases, List<TraceEvent> events) {
    }

    /** 批量装载响应：loaded 为实际装入的当前标签（排序）。 */
    public record LoadResponse(String legId, String status, int version, List<String> loaded) {
    }

    /** 封舱/到达清单中冻结的行李条目：稳定ID、封舱当时标签与代次。 */
    public record ManifestItem(String bagId, String bagTag, int tagGeneration) {
    }

    /** 封舱响应：manifest 为冻结的只读装载清单快照。 */
    public record SealResponse(String legId, String status, int version, List<ManifestItem> manifest) {
    }

    /** 到达确认响应。 */
    public record ArriveResponse(String legId, String status, int version, List<ManifestItem> arrived) {
    }

    /** 差异到达响应：arrived 为实际到达，shortUnloaded 为清单中缺失并转短卸的条目。 */
    public record DifferenceArriveResponse(String legId, String status, int version,
                                           List<ManifestItem> arrived, List<ManifestItem> shortUnloaded) {
    }

    /** 换标响应。 */
    public record RetagResponse(String bagId, String oldBagTag, String bagTag, int tagGeneration,
                                String status, String currentLocation) {
    }

    /** 补到响应。 */
    public record RecoverResponse(String bagId, String bagTag, int tagGeneration, String status,
                                  String currentLocation, int nextLegIndex, String recoveredLegId) {
    }

    /** 封舱清单查询响应。 */
    public record ManifestResponse(String legId, String status, int version, List<ManifestItem> manifest) {
    }

    /** 航段差异快照响应：arrivalType 为 EXACT/DIFF，actual 仅差异到达时有值。 */
    public record LegDifferenceResponse(String legId, String status, int version,
                                        List<ManifestItem> manifest, String arrivalType,
                                        List<ManifestItem> actual) {
    }

    /** 未补到行李清单项。 */
    public record ShortItem(String bagId, String bagTag, int tagGeneration, String missingLegId,
                            String expectedStation, String registeredAt,
                            int nextLegIndex, String currentLocation) {
    }

    /** 未补到清单响应。 */
    public record ShortListResponse(List<ShortItem> shortUnloaded) {
    }
}
