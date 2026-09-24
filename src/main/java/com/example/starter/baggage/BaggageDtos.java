package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.constraints.Max;
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

    /** 登记航段请求：maxBags 件数上限 1~500，maxWeight 总重上限 1~50000 千克。 */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination,
            @NotNull(message = "maxBags 不能为空")
            @Min(value = 1, message = "航段件数上限必须为 1~500")
            @Max(value = 500, message = "航段件数上限必须为 1~500") Integer maxBags,
            @NotNull(message = "maxWeight 不能为空")
            @Min(value = 1, message = "航段总重上限必须为 1~50000 千克")
            @Max(value = 50000, message = "航段总重上限必须为 1~50000 千克") Integer maxWeight) {
    }

    /** 登记行李请求：legIds 为 1~5 个有序航段；weight 1~50 千克整数，cabin 为 PREMIUM/STANDARD/BASIC。 */
    public record RegisterBagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "legIds 不能为空")
            @Size(min = 1, max = 5, message = "行程航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> legIds,
            @NotNull(message = "weight 不能为空")
            @Min(value = 1, message = "行李重量必须为 1~50 千克")
            @Max(value = 50, message = "行李重量必须为 1~50 千克") Integer weight,
            @NotBlank(message = "cabin 不能为空") String cabin) {
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

    /**
     * 容量卸载请求：offloadKey 全局唯一，expectedVersion 做并发版本校验，
     * targetMaxBags/targetMaxWeight 为卸载后期望的件数与总重上限，允许 0（全部卸出），不得高于航段登记上限。
     */
    public record OffloadRequest(
            @NotBlank(message = "offloadKey 不能为空") String offloadKey,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "targetMaxBags 不能为空")
            @Min(value = 0, message = "目标件数上限必须为 0~500")
            @Max(value = 500, message = "目标件数上限必须为 0~500") Integer targetMaxBags,
            @NotNull(message = "targetMaxWeight 不能为空")
            @Min(value = 0, message = "目标总重上限必须为 0~50000 千克")
            @Max(value = 50000, message = "目标总重上限必须为 0~50000 千克") Integer targetMaxWeight) {
    }

    /** 航段响应。 */
    public record LegResponse(String legId, String origin, String destination,
                              String status, int version, int maxBags, int maxWeight) {
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
                              String status, int weight, String cabin, String loadedLegId,
                              List<ItineraryItem> itinerary,
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

    /** 被卸行李清单项：含卸载时的原待乘索引（0 起，卸载不推进）。 */
    public record OffloadedItem(String bagTag, int weight, String cabin, int originNextLegIndex) {
    }

    /**
     * 卸载决策响应：offloaded 为按 BASIC→STANDARD→PREMIUM、同级重量降序/bagTag 升序确定的被卸清单，
     * retained 为继续保留在航段清单中的袋号；目标本已满足时 offloaded 为空，version 不变化。
     */
    public record OffloadResponse(String offloadKey, String legId, String status, int version,
                                  int targetMaxBags, int targetMaxWeight,
                                  int retainedBags, int retainedWeight,
                                  List<OffloadedItem> offloaded, List<String> retained) {
    }

    /** 航段载量占用响应：occupiedBags/occupiedWeight 为当前清单占用，含剩余额度。 */
    public record CapacityResponse(String legId, String status, int version,
                                   int maxBags, int maxWeight,
                                   int occupiedBags, int occupiedWeight,
                                   int remainingBags, int remainingWeight) {
    }

    /** 单次卸载明细项。 */
    public record OffloadItemResponse(String bagTag, int weight, String cabin,
                                      int originNextLegIndex, String offloadedAt) {
    }

    /** 单次卸载决策明细：含目标上限、卸载后保留占用与逐件明细。 */
    public record OffloadDetailResponse(String offloadKey, String legId,
                                        int targetMaxBags, int targetMaxWeight,
                                        int offloadedCount, int offloadedWeight,
                                        int retainedCount, int retainedWeight,
                                        String offloadedAt, List<OffloadItemResponse> items) {
    }

    /** 航段卸载明细列表响应，按卸载时刻倒序。 */
    public record OffloadListResponse(String legId, List<OffloadDetailResponse> offloads) {
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
}
