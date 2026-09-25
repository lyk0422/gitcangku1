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

    /** 登记航段请求。 */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination) {
    }

    /** 登记行李请求：legIds 为 1~5 个有序航段；weightKg 可选初始记录重量，freeAllowanceKg 可选免费限额（默认 20）。 */
    public record RegisterBagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "legIds 不能为空")
            @Size(min = 1, max = 5, message = "行程航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> legIds,
            @Min(value = 1, message = "初始重量必须为 1~50 千克") @Max(value = 50, message = "初始重量必须为 1~50 千克") Integer weightKg,
            @Min(value = 1, message = "免费限额必须为正整数") @Max(value = 50, message = "免费限额必须为 1~50 千克") Integer freeAllowanceKg) {
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

    /** 复重请求：reweighKey 为复重业务键，measuredWeightKg 为 1~50 千克整数实测重量。 */
    public record ReweighRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "reweighKey 不能为空") String reweighKey,
            @NotNull(message = "measuredWeightKg 不能为空")
            @Min(value = 1, message = "实测重量必须为 1~50 千克整数")
            @Max(value = 50, message = "实测重量必须为 1~50 千克整数") Integer measuredWeightKg,
            @NotBlank(message = "stationId 不能为空") String stationId) {
    }

    /** 清除超重提醒请求：必须携带说明，清除不可逆。 */
    public record ClearOverweightRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "说明不能为空") String note) {
    }

    /** 航段响应。 */
    public record LegResponse(String legId, String origin, String destination,
                              String status, int version) {
    }

    /** 行程明细项。 */
    public record ItineraryItem(int seq, String legId, String origin, String destination) {
    }

    /** 行李响应：weightKg 为当前记录重量（未称重为 null），overweightReminder 为超重提醒状态。 */
    public record BagResponse(String bagTag, String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
                              Integer weightKg, Integer freeAllowanceKg, String overweightReminder) {
    }

    /** 批量装载响应：totalWeightKg 为按装载时最新重量计算的批总重；
     *  overweightReminders 为本批中未清除超重提醒的袋号清单，供人工确认，不阻止装载。 */
    public record LoadResponse(String legId, String status, int version, List<String> loaded,
                               int totalWeightKg, List<String> overweightReminders) {
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

    /** 复重响应：changed 表示记录重量是否被实测值改写；overweightReminder 为复重后的提醒状态。 */
    public record ReweighResponse(String bagTag, String reweighKey, Integer previousWeightKg,
                                  int weightKg, boolean changed, String stationId, String weighedAt,
                                  String overweightReminder) {
    }

    /** 清除超重提醒响应。 */
    public record ClearOverweightResponse(String bagTag, String overweightReminder,
                                          String note, String clearedAt) {
    }

    /** 复重记录明细项（不可变历史）。 */
    public record ReweighRecordItem(String reweighKey, Integer oldWeightKg, int newWeightKg,
                                    boolean weightChanged, String stationId, String weighedAt) {
    }

    /** 复重历史与当前提醒状态查询响应。 */
    public record ReweighHistoryResponse(String bagTag, Integer weightKg, int freeAllowanceKg,
                                         String overweightReminder, String overweightClearNote,
                                         List<ReweighRecordItem> history) {
    }
}
