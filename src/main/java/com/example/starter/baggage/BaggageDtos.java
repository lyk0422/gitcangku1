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

    /**
     * 批量装载请求：1~20 个不重复 bagTag，必须使用各行李当前标签；
     * expectedTagGeneration 可选，提供时要求各行李当前代次一致，否则 409。
     */
    public record LoadRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 20, message = "批量装载数量必须为 1~20") List<@NotBlank(message = "bagTag 不能为空") String> bagTags,
            Integer expectedTagGeneration) {

        /** 兼容构造：不校验标签代次。 */
        public LoadRequest(String requestId, Integer expectedVersion, List<String> bagTags) {
            this(requestId, expectedVersion, bagTags, null);
        }
    }

    /** 封舱请求。 */
    public record SealRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion) {
    }

    /** 到达确认请求：实际袋号集合，须与封舱清单完全一致（按稳定实物标识比对，任一历史标签均可解析）。 */
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

    /**
     * 补到请求：短卸行李在缺失航段的应到站实际到达；bagTag 必须为当前标签，
     * expectedTagGeneration 可选，提供时要求与当前代次一致，否则 409。
     */
    public record RecoverRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "missingLegId 不能为空") String missingLegId,
            @NotBlank(message = "actualStation 不能为空") String actualStation,
            Integer expectedTagGeneration) {

        /** 兼容构造：不校验标签代次。 */
        public RecoverRequest(String requestId, String bagTag, String missingLegId, String actualStation) {
            this(requestId, bagTag, missingLegId, actualStation, null);
        }
    }

    /**
     * 换标请求：指定稳定实物标识、期望代次与全局未用的新标签；
     * operator 为非空操作者，参与幂等身份摘要。
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

    /** 行李轨迹事件：bagTag 为事件发生时刻的当前标签，历史证据不随换标倒改。 */
    public record TraceEvent(int seq, String eventType, String legId,
                             String location, String eventTime, String bagTag) {
    }

    /** 行李响应：bagId 为稳定实物标识，bagTag 为当前标签，tagGeneration 为当前代次，含完整事件轨迹。 */
    public record BagResponse(String bagTag, String bagId, int tagGeneration,
                              String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
                              String shortLegId, String shortDestination, String shortRegisteredAt,
                              List<TraceEvent> events) {
    }

    /** 批量装载响应。 */
    public record LoadResponse(String legId, String status, int version, List<String> loaded) {
    }

    /** 封舱响应：manifest 为只读装载清单快照（封舱时刻的标签号）。 */
    public record SealResponse(String legId, String status, int version, List<String> manifest) {
    }

    /** 到达确认响应。 */
    public record ArriveResponse(String legId, String status, int version, List<String> arrived) {
    }

    /** 差异到达响应：arrived 为实际到达，shortUnloaded 为清单中缺失并转短卸的袋号（均为清单冻结标签）。 */
    public record DifferenceArriveResponse(String legId, String status, int version,
                                           List<String> arrived, List<String> shortUnloaded) {
    }

    /** 补到响应。 */
    public record RecoverResponse(String bagTag, String status, String currentLocation,
                                  int nextLegIndex, String recoveredLegId) {
    }

    /** 换标响应：返回稳定实物标识、新当前标签与递增后的代次。 */
    public record RetagResponse(String bagId, String bagTag, String previousBagTag, int tagGeneration) {
    }

    /** 封舱清单查询响应：manifest 为封舱时刻冻结的标签号。 */
    public record ManifestResponse(String legId, String status, int version, List<String> manifest) {
    }

    /** 航段差异快照响应：arrivalType 为 EXACT/DIFF，actual 仅差异到达时有值。 */
    public record LegDifferenceResponse(String legId, String status, int version,
                                        List<String> manifest, String arrivalType, List<String> actual) {
    }

    /** 未补到行李清单项：bagTag 为当前标签。 */
    public record ShortItem(String bagTag, String missingLegId, String expectedStation,
                            String registeredAt, int nextLegIndex, String currentLocation) {
    }

    /** 未补到清单响应。 */
    public record ShortListResponse(List<ShortItem> shortUnloaded) {
    }
}
