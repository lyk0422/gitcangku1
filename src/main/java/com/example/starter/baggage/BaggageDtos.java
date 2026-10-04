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

    /** 登记行李请求：legIds 为 1~5 个有序航段；passengerId 可选，绑定合成旅客编号。 */
    public record RegisterBagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "legIds 不能为空")
            @Size(min = 1, max = 5, message = "行程航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> legIds,
            String passengerId) {
        /** 兼容无旅客绑定的登记请求。 */
        public RegisterBagRequest(String requestId, String bagTag, List<String> legIds) {
            this(requestId, bagTag, legIds, null);
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
                              String status, int version) {
    }

    /** 行程明细项。 */
    public record ItineraryItem(int seq, String legId, String origin, String destination) {
    }

    /** 行李轨迹事件。 */
    public record TraceEvent(int seq, String eventType, String legId,
                             String location, String eventTime) {
    }

    /** 行李响应：含完整事件轨迹；passengerId 为绑定的合成旅客编号，未绑定为 null。 */
    public record BagResponse(String bagTag, String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, String passengerId,
                              List<ItineraryItem> itinerary,
                              String shortLegId, String shortDestination, String shortRegisteredAt,
                              List<TraceEvent> events) {
    }

    /** 批量装载响应。 */
    public record LoadResponse(String legId, String status, int version, List<String> loaded) {
    }

    /** 封舱响应：manifest 为只读装载清单快照，passengers 为封舱时冻结的登机名单快照。 */
    public record SealResponse(String legId, String status, int version, List<String> manifest,
                               List<String> passengers) {
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

    /**
     * 登机名单更新请求：add/remove/unloadBagTags 均按集合处理（重复编号 400）。
     * 移除已装载行李的旅客时，unloadBagTags 必须恰好等于这些旅客在本航段的全部已装载袋号集合，
     * 漏项或夹带其他旅客行李均 422 且整单不变；expectedLegVersion 按航段当前版本裁决。
     */
    public record UpdatePassengersRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "expectedLegVersion 不能为空") Integer expectedLegVersion,
            @NotNull(message = "add 不能为空")
            List<@NotBlank(message = "旅客编号不能为空") String> add,
            @NotNull(message = "remove 不能为空")
            List<@NotBlank(message = "旅客编号不能为空") String> remove,
            @NotNull(message = "unloadBagTags 不能为空")
            List<@NotBlank(message = "bagTag 不能为空") String> unloadBagTags) {
    }

    /** 登机名单更新响应：boarded 为更新后的完整登机名单，unloaded 为本次安全卸载的袋号。 */
    public record PassengerUpdateResponse(String legId, String status, int version,
                                          List<String> boarded, List<String> unloaded) {
    }

    /**
     * 登机名单查询响应：passengers 为当前登机名单；
     * 封舱后 sealedPassengerVersion/sealedPassengers/sealedPassengerBags 为冻结快照，未封舱为 null。
     */
    public record PassengerListResponse(String legId, String status, int version,
                                        List<String> passengers,
                                        Integer sealedPassengerVersion,
                                        List<String> sealedPassengers,
                                        java.util.Map<String, List<String>> sealedPassengerBags) {
    }

    /** 未补到行李清单项。 */
    public record ShortItem(String bagTag, String missingLegId, String expectedStation,
                            String registeredAt, int nextLegIndex, String currentLocation) {
    }

    /** 未补到清单响应。 */
    public record ShortListResponse(List<ShortItem> shortUnloaded) {
    }
}
