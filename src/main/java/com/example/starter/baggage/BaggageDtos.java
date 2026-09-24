package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 联程行李装载交接 API 的请求与响应 DTO。
 */
public final class BaggageDtos {

    private BaggageDtos() {
    }

    /** 登记航段请求：件数上限 1~500，总重上限 1~50000 千克；缺省为最宽松上限。 */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination,
            @Min(value = 1, message = "件数上限必须为 1~500")
            @Max(value = 500, message = "件数上限必须为 1~500") Integer maxPieces,
            @Min(value = 1, message = "总重上限必须为 1~50000 千克")
            @Max(value = 50000, message = "总重上限必须为 1~50000 千克") Integer maxWeightKg) {

        /** 规范化构造：JSON 缺省载量字段时取最宽松的 500 件 / 50000 千克。 */
        public RegisterLegRequest {
            if (maxPieces == null) {
                maxPieces = 500;
            }
            if (maxWeightKg == null) {
                maxWeightKg = 50000;
            }
        }

        /** 兼容旧调用：不显式指定载量上限时使用最宽松的 500 件 / 50000 千克。 */
        public RegisterLegRequest(String requestId, String legId, String origin, String destination) {
            this(requestId, legId, origin, destination, 500, 50000);
        }
    }

    /** 登记行李请求：legIds 为 1~5 个有序航段，重量 1~50 千克整数，舱位 PREMIUM/STANDARD/BASIC。 */
    public record RegisterBagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "legIds 不能为空")
            @Size(min = 1, max = 5, message = "行程航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> legIds,
            @Min(value = 1, message = "行李重量必须为 1~50 千克")
            @Max(value = 50, message = "行李重量必须为 1~50 千克") Integer weightKg,
            @Pattern(regexp = "PREMIUM|STANDARD|BASIC", message = "舱位等级必须为 PREMIUM/STANDARD/BASIC")
            String cabinClass) {

        /** 规范化构造：JSON 缺省重量/舱位时默认 10 千克 STANDARD。 */
        public RegisterBagRequest {
            if (weightKg == null) {
                weightKg = 10;
            }
            if (cabinClass == null || cabinClass.isBlank()) {
                cabinClass = "STANDARD";
            }
        }

        /** 兼容旧调用：不显式指定重量与舱位时默认 10 千克 STANDARD。 */
        public RegisterBagRequest(String requestId, String bagTag, List<String> legIds) {
            this(requestId, bagTag, legIds, 10, "STANDARD");
        }
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

    /**
     * 卸载决策请求：offloadKey 全局唯一；expectedVersion 做并发版本校验；
     * 目标件数/总重上限不得高于航段登记上限。
     */
    public record OffloadRequest(
            @NotBlank(message = "offloadKey 不能为空") String offloadKey,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "targetMaxPieces 不能为空")
            @Min(value = 1, message = "目标件数上限必须为 1~500")
            @Max(value = 500, message = "目标件数上限必须为 1~500") Integer targetMaxPieces,
            @NotNull(message = "targetMaxWeightKg 不能为空")
            @Min(value = 1, message = "目标总重上限必须为 1~50000 千克")
            @Max(value = 50000, message = "目标总重上限必须为 1~50000 千克") Integer targetMaxWeightKg) {
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

    /** 改派/补到请求：OFFLOADED 行李沿用剩余行程重新装载前的恢复入口。 */
    public record RecoverRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "missingLegId 不能为空") String missingLegId,
            @NotBlank(message = "actualStation 不能为空") String actualStation) {
    }

    /** 航段响应。 */
    public record LegResponse(String legId, String origin, String destination,
                              String status, int version, int maxPieces, int maxWeightKg) {
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
                              String status, int weightKg, String cabinClass, String loadedLegId,
                              List<ItineraryItem> itinerary,
                              String shortLegId, String shortDestination, String shortRegisteredAt,
                              String offloadLegId, Integer offloadNextIndex, String offloadedAt,
                              List<TraceEvent> events) {
    }

    /** 批量装载响应。 */
    public record LoadResponse(String legId, String status, int version, List<String> loaded) {
    }

    /** 卸载决策清单项：被卸或保留行李的重量与舱位。 */
    public record OffloadBagItem(String bagTag, int weightKg, String cabinClass) {
    }

    /** 卸载决策响应：offloaded 为被卸清单（按决策顺序），retained 为保留清单（按 bagTag 升序）。 */
    public record OffloadResponse(String offloadKey, String legId, String status, int version,
                                  int targetMaxPieces, int targetMaxWeightKg,
                                  int occupiedPieces, int occupiedWeightKg,
                                  List<OffloadBagItem> offloaded, List<OffloadBagItem> retained) {
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

    /** 补到/改派响应。 */
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

    /** 航段载量占用查询响应：占用只统计当前仍在装载清单内的行李。 */
    public record OccupancyResponse(String legId, String status, int version,
                                    int maxPieces, int maxWeightKg,
                                    int occupiedPieces, int occupiedWeightKg,
                                    int remainingPieces, int remainingWeightKg) {
    }

    /** 卸载明细项：一次卸载决策中的一件被卸行李。 */
    public record OffloadItem(String offloadKey, String legId, String bagTag,
                              int originIndex, int weightKg, String cabinClass,
                              int targetMaxPieces, int targetMaxWeightKg, String offloadedAt) {
    }

    /** 卸载明细查询响应。 */
    public record OffloadListResponse(String legId, List<OffloadItem> offloads) {
    }

    /** 未补到行李清单项。 */
    public record ShortItem(String bagTag, String missingLegId, String expectedStation,
                            String registeredAt, int nextLegIndex, String currentLocation) {
    }

    /** 未补到清单响应。 */
    public record ShortListResponse(List<ShortItem> shortUnloaded) {
    }
}
