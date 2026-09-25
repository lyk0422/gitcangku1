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
            @NotBlank(message = "destination 不能为空") String destination,
            String originCountry,
            String destinationCountry) {

        /** 国内航段便捷构造器：始发/目的国缺省 CN。 */
        public RegisterLegRequest(String requestId, String legId, String origin, String destination) {
            this(requestId, legId, origin, destination, "CN", "CN");
        }
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
                              String originCountry, String destinationCountry,
                              String status, int version) {
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
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
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

    /** 海关检查登记请求：checkVersion 为行李内单调递增的检查版本，status 为 RELEASED/HELD。 */
    public record CustomsCheckRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "checkVersion 不能为空") Integer checkVersion,
            @NotBlank(message = "status 不能为空") String status,
            @NotBlank(message = "country 不能为空") String country,
            String reason) {
    }

    /** 起飞请求：航段须已封舱，且机上所有行李通过目的国持续门禁。 */
    public record DepartRequest(
            @NotBlank(message = "requestId 不能为空") String requestId) {
    }

    /** 改派请求：以新的有序航段替换行李尚未乘坐的后续行程，首段始发站须为行李当前所在站。 */
    public record RerouteRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "newLegIds 不能为空")
            @Size(min = 1, max = 5, message = "改派航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> newLegIds) {
    }

    /** 检查链中的单条检查终态。 */
    public record InspectionItem(int checkVersion, String status, String country,
                                 String reason, String checkedAt, String clearanceKey) {
    }

    /** 海关检查登记响应：heldLegs 为本次拦截被标记 CUSTOMS_HOLD 的未起飞航段。 */
    public record CustomsCheckResponse(String bagTag, int checkVersion, String status, String country,
                                       String reason, String checkedAt, String clearanceKey,
                                       List<String> heldLegs) {
    }

    /** 行李检查链查询响应：含全部历史终态与当前各航段持续门禁。 */
    public record InspectionChainResponse(String bagTag, List<InspectionItem> inspections,
                                          List<GateItem> gates) {
    }

    /** 航段/行李门禁项：gateStatus 为 RELEASED 或 CUSTOMS_HOLD。 */
    public record GateItem(String bagTag, String legId, String country, String gateStatus,
                           int checkVersion, String updatedAt, String snapshot) {
    }

    /** 航段门禁查询响应。 */
    public record LegGateResponse(String legId, String legStatus, String destinationCountry,
                                  List<GateItem> gates) {
    }

    /** 行李拦截影响查询响应：仅含当前仍为 CUSTOMS_HOLD 的门禁及只读快照。 */
    public record HoldImpactResponse(String bagTag, List<GateItem> holds) {
    }

    /** 起飞响应。 */
    public record DepartResponse(String legId, String status, int version, List<String> departed) {
    }

    /** 改派响应：releasedLegId 为改派时从 OPEN 航段卸下的原航段，未装载为 null。 */
    public record RerouteResponse(String bagTag, String status, String currentLocation,
                                  int nextLegIndex, String releasedLegId, List<ItineraryItem> itinerary) {
    }
}
