package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    /** 登记航段请求：maxLoadWeightKg 缺省取服务端默认值 1000 千克。 */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination,
            @Positive(message = "航段总重上限必须为正整数") Integer maxLoadWeightKg) {
    }

    /** 登记行李请求：legIds 为 1~5 个有序航段；重量/限额缺省取服务端默认值。 */
    public record RegisterBagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "legIds 不能为空")
            @Size(min = 1, max = 5, message = "行程航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> legIds,
            @Min(value = 1, message = "行李重量必须为 1~50 千克整数")
            @Max(value = 50, message = "行李重量必须为 1~50 千克整数") Integer weightKg,
            @Positive(message = "免费限额必须为正整数") Integer freeAllowanceKg) {
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

    /** 复重纠偏请求：实测重量 1~50 千克整数。 */
    public record ReweighRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "reweighKey 不能为空") String reweighKey,
            @NotNull(message = "measuredWeightKg 不能为空")
            @Min(value = 1, message = "实测重量必须为 1~50 千克整数")
            @Max(value = 50, message = "实测重量必须为 1~50 千克整数") Integer measuredWeightKg,
            @NotBlank(message = "stationId 不能为空") String stationId) {
    }

    /** 清除超重提醒请求：清除不可逆，必须提交超额说明。 */
    public record ClearOverweightRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "explanation 不能为空") String explanation) {
    }

    /** 航段响应。 */
    public record LegResponse(String legId, String origin, String destination,
                              String status, int version, int maxLoadWeightKg) {
    }

    /** 行程明细项。 */
    public record ItineraryItem(int seq, String legId, String origin, String destination) {
    }

    /** 行李响应：overweightActive 表示是否存在未清除的 OVERWEIGHT 提醒。 */
    public record BagResponse(String bagTag, String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
                              int weightKg, int freeAllowanceKg, boolean overweightActive) {
    }

    /** 装载响应中的超重提醒项：装载不拦截，供人工确认。 */
    public record OverweightWarning(String bagTag, int weightKg, int journeyWeightKg,
                                    int freeAllowanceKg, String raisedAt) {
    }

    /**
     * 批量装载响应：totalWeightKg 为清单内行李按“最新记录重量”计算的合计，
     * overweightWarnings 为清单中存在未清除 OVERWEIGHT 提醒的行李。
     */
    public record LoadResponse(String legId, String status, int version, List<String> loaded,
                               int totalWeightKg, int maxLoadWeightKg,
                               List<OverweightWarning> overweightWarnings) {
    }

    /** 封舱响应：manifest 为只读装载清单快照。 */
    public record SealResponse(String legId, String status, int version, List<String> manifest) {
    }

    /** 到达确认响应。 */
    public record ArriveResponse(String legId, String status, int version, List<String> arrived) {
    }

    /** 封舱清单查询响应。 */
    public record ManifestResponse(String legId, String status, int version, List<String> manifest) {
    }

    /** 复重历史中的单条不可变事件。 */
    public record ReweighHistoryItem(int seq, String reweighKey, int oldWeightKg, int newWeightKg,
                                     boolean weightChanged, String stationId, String weighedAt,
                                     boolean overweightRaised) {
    }

    /** 复重结果：返回复重后重量、整程累计重量与最新提醒状态。 */
    public record ReweighResponse(String bagTag, int weightKg, int journeyWeightKg,
                                  int freeAllowanceKg, boolean overweightActive,
                                  int seq, String weighedAt) {
    }

    /** 清除超重提醒结果。 */
    public record ClearOverweightResponse(String bagTag, boolean overweightActive,
                                          String explanation, String clearedAt) {
    }

    /** 行李复重历史与当前提醒状态查询响应。 */
    public record ReweighHistoryResponse(String bagTag, int weightKg, int journeyWeightKg,
                                         int freeAllowanceKg, boolean overweightActive,
                                         List<ReweighHistoryItem> history) {
    }

    /** 提醒明细项：ACTIVE 为未清除，CLEARED 携带超额说明与清除时刻。 */
    public record AlertItem(int seq, String status, int journeyWeightKg, int freeAllowanceKg,
                            String raisedAt, String reweighKey,
                            String clearedExplanation, String clearedAt) {
    }

    /** 当前提醒状态查询响应：含全部历史提醒（含已清除）。 */
    public record OverweightStatusResponse(String bagTag, boolean overweightActive,
                                           List<AlertItem> alerts) {
    }
}
