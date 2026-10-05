package com.example.starter.baggage;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 联程行李装载交接 API 的请求与响应 DTO。
 */
public final class BaggageDtos {

    private BaggageDtos() {
    }

    /** 登记航段请求：kind 缺省 CLASSIC（旧航段），HOLD 为新类型固定前后两舱航段。 */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination,
            String kind) {

        /** 兼容旧调用：缺省 CLASSIC 航段。 */
        public RegisterLegRequest(String requestId, String legId, String origin, String destination) {
            this(requestId, legId, origin, destination, null);
        }
    }

    /** 登记行李请求：legIds 为 1~5 个有序航段；massKg 为不可变正质量（最多 3 位小数），可缺省。 */
    public record RegisterBagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "legIds 不能为空")
            @Size(min = 1, max = 5, message = "行程航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> legIds,
            @DecimalMin(value = "0", inclusive = false, message = "行李质量必须为正值")
            @Digits(integer = 7, fraction = 3, message = "行李质量最多 3 位小数") BigDecimal massKg) {

        /** 兼容旧调用：不登记质量。 */
        public RegisterBagRequest(String requestId, String bagTag, List<String> legIds) {
            this(requestId, bagTag, legIds, null);
        }
    }

    /** 舱位指派：bagTag 装入 holdCode（FWD 前舱 / AFT 后舱）。 */
    public record HoldAssignment(
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "holdCode 不能为空") String holdCode) {
    }

    /**
     * 批量装载请求：1~20 个不重复 bagTag。
     * operator 为操作者标识，参与幂等身份；holds 为舱位指派，仅 HOLD 航段需要且须覆盖全部 bagTag。
     */
    public record LoadRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            String operator,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 20, message = "批量装载数量必须为 1~20") List<@NotBlank(message = "bagTag 不能为空") String> bagTags,
            List<@NotNull(message = "舱位指派不能为空") HoldAssignment> holds) {

        /** 兼容旧调用：无操作者、无舱位指派。 */
        public LoadRequest(String requestId, Integer expectedVersion, List<String> bagTags) {
            this(requestId, null, expectedVersion, bagTags, null);
        }
    }

    /** 封舱请求：operator 为操作者标识，参与幂等身份。 */
    public record SealRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            String operator,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion) {

        /** 兼容旧调用：无操作者。 */
        public SealRequest(String requestId, Integer expectedVersion) {
            this(requestId, null, expectedVersion);
        }
    }

    /**
     * 批量换舱请求：仅 OPEN 的 HOLD 航段可用，assignments 为 1~20 件已装载行李的最终舱位；
     * 按最终全体位置一次性校验舱重，不逐件搬运。
     */
    public record ReholdRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "assignments 不能为空")
            @Size(min = 1, max = 20, message = "批量换舱数量必须为 1~20")
            List<@NotNull(message = "舱位指派不能为空") HoldAssignment> assignments) {
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

    /** 航段响应：kind 为 CLASSIC/HOLD。 */
    public record LegResponse(String legId, String origin, String destination,
                              String status, int version, String kind) {

        /** 兼容旧调用：CLASSIC 航段。 */
        public LegResponse(String legId, String origin, String destination, String status, int version) {
            this(legId, origin, destination, status, version, "CLASSIC");
        }
    }

    /** 行程明细项。 */
    public record ItineraryItem(int seq, String legId, String origin, String destination) {
    }

    /** 行李轨迹事件。 */
    public record TraceEvent(int seq, String eventType, String legId,
                             String location, String eventTime) {
    }

    /** 行李响应：含完整事件轨迹；massKg 为登记质量（千克），未登记为 null。 */
    public record BagResponse(String bagTag, String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
                              String shortLegId, String shortDestination, String shortRegisteredAt,
                              BigDecimal massKg, List<TraceEvent> events) {
    }

    /** 批量装载响应。 */
    public record LoadResponse(String legId, String status, int version, List<String> loaded) {
    }

    /**
     * 封舱响应：manifest 为只读装载清单快照；
     * totalMassKg/momentKgm 为 HOLD 航段封舱冻结的总质量（千克）与精确力矩（千克·米），旧航段为 null。
     */
    public record SealResponse(String legId, String status, int version, List<String> manifest,
                               BigDecimal totalMassKg, BigDecimal momentKgm) {

        /** 兼容旧调用：无冻结平衡数据。 */
        public SealResponse(String legId, String status, int version, List<String> manifest) {
            this(legId, status, version, manifest, null, null);
        }
    }

    /** 批量换舱响应：changed 为实际变更舱位的袋号（按袋号排序）。 */
    public record ReholdResponse(String legId, String status, int version, List<String> changed) {
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

    /** 封舱冻结的单件平衡明细：质量（千克）、舱位与纵向位置（米，FWD -2 / AFT 3）。 */
    public record SealedBagDetail(String bagTag, BigDecimal massKg, String holdCode, int positionM) {
    }

    /**
     * 封舱清单查询响应；
     * totalMassKg/momentKgm/detail 为 HOLD 航段封舱冻结数据，未封舱或旧航段为 null。
     */
    public record ManifestResponse(String legId, String status, int version, List<String> manifest,
                                   BigDecimal totalMassKg, BigDecimal momentKgm,
                                   List<SealedBagDetail> detail) {

        /** 兼容旧调用：无冻结平衡数据。 */
        public ManifestResponse(String legId, String status, int version, List<String> manifest) {
            this(legId, status, version, manifest, null, null, null);
        }
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
