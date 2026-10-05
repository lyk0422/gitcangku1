package com.example.starter.baggage;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 联程行李装载交接 API 的请求与响应 DTO。
 */
public final class BaggageDtos {

    private BaggageDtos() {
    }

    /** 登记航段请求：legType 缺省为 STANDARD；COMPARTMENT 为固定前后两舱的双舱航段。 */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination,
            @Pattern(regexp = "STANDARD|COMPARTMENT", message = "legType 仅支持 STANDARD/COMPARTMENT")
            String legType) {

        /** 兼容旧登记：缺省 STANDARD 普通航段。 */
        public RegisterLegRequest(String requestId, String legId, String origin, String destination) {
            this(requestId, legId, origin, destination, null);
        }
    }

    /** 登记行李请求：legIds 为 1~5 个有序航段；massKg 为正、最多 3 位小数、登记后不可变。 */
    public record RegisterBagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "legIds 不能为空")
            @Size(min = 1, max = 5, message = "行程航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> legIds,
            @Positive(message = "massKg 必须为正值")
            @Digits(integer = 10, fraction = 3, message = "massKg 最多 3 位小数") BigDecimal massKg) {

        /** 兼容旧登记：不登记质量。 */
        public RegisterBagRequest(String requestId, String bagTag, List<String> legIds) {
            this(requestId, bagTag, legIds, null);
        }
    }

    /** 行李舱位指定：compartment 仅支持 FWD（前舱 -2 米）/AFT（后舱 3 米）。 */
    public record BagCompartment(
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "compartment 不能为空")
            @Pattern(regexp = "FWD|AFT", message = "compartment 仅支持 FWD/AFT") String compartment) {
    }

    /** 批量装载请求：1~20 个不重复 bagTag；双舱航段必须同时为每件行李指定舱位。 */
    public record LoadRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 20, message = "批量装载数量必须为 1~20") List<@NotBlank(message = "bagTag 不能为空") String> bagTags,
            String operator,
            @Size(max = 20, message = "舱位指定数量不能超过批量大小") List<@Valid BagCompartment> compartments) {

        /** 兼容旧装载：普通航段无需舱位与操作者。 */
        public LoadRequest(String requestId, Integer expectedVersion, List<String> bagTags) {
            this(requestId, expectedVersion, bagTags, null, null);
        }
    }

    /** 封舱请求。 */
    public record SealRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            String operator) {

        /** 兼容旧封舱：无操作者。 */
        public SealRequest(String requestId, Integer expectedVersion) {
            this(requestId, expectedVersion, null);
        }
    }

    /** 批量换舱请求：仅开放的双舱航段可用，按最终全体位置一次性校验舱重。 */
    public record MoveCompartmentRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            String operator,
            @NotNull(message = "moves 不能为空")
            @Size(min = 1, max = 20, message = "批量换舱数量必须为 1~20") List<@Valid BagCompartment> moves) {
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
                              String status, int version, String legType) {
    }

    /** 行程明细项。 */
    public record ItineraryItem(int seq, String legId, String origin, String destination) {
    }

    /** 行李轨迹事件。 */
    public record TraceEvent(int seq, String eventType, String legId,
                             String location, String eventTime) {
    }

    /** 行李响应：含完整事件轨迹；massKg 为登记后不可变的质量（千克），未登记为 null。 */
    public record BagResponse(String bagTag, String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
                              String shortLegId, String shortDestination, String shortRegisteredAt,
                              List<TraceEvent> events, BigDecimal massKg) {
    }

    /** 批量装载响应：assignments 为双舱航段本次装载的舱位指定（按 bagTag 排序），普通航段为 null。 */
    public record LoadResponse(String legId, String status, int version, List<String> loaded,
                               List<BagCompartment> assignments) {

        /** 兼容旧装载响应：无舱位指定。 */
        public LoadResponse(String legId, String status, int version, List<String> loaded) {
            this(legId, status, version, loaded, null);
        }
    }

    /** 批量换舱响应：moved 为实际换舱的袋号（排序后）。 */
    public record MoveCompartmentResponse(String legId, String status, int version, List<String> moved) {
    }

    /** 封舱逐件冻结项：质量、舱位、纵向位置与精确力矩。 */
    public record BalanceItem(String bagTag, BigDecimal massKg, String compartment,
                              BigDecimal positionM, BigDecimal momentKgm) {
    }

    /**
     * 航段重心平衡响应：totalMassKg/totalMomentKgm 为精确值（千克 / 千克·米）；
     * centerOfGravityM 为展示用重心（米，6 位小数舍入），封舱判定不依赖该舍入值；
     * 开放航段为实时计算，封舱后为冻结快照，到达与短卸不改变历史值。
     */
    public record BalanceResponse(String legId, String status, int version,
                                  BigDecimal totalMassKg, BigDecimal totalMomentKgm,
                                  BigDecimal centerOfGravityM, List<BalanceItem> items) {
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
}
