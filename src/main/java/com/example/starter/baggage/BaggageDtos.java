package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.Valid;
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

    /** 建组请求：2~5 件同行程、同当前站且从未装载的行李固定为一组。 */
    public record FormGroupRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "groupKey 不能为空") String groupKey,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 2, max = 5, message = "组成员数量必须为 2~5")
            List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 认领成员及其当前轨迹版本（bag.version，每次轨迹事件后递增）。 */
    public record ClaimMember(
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion) {
    }

    /** 整组显式认领请求：必须提交全部成员及各成员当前轨迹版本。 */
    public record ClaimGroupRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "members 不能为空")
            @Size(min = 2, max = 5, message = "认领成员数量必须为 2~5")
            List<@Valid @NotNull(message = "成员不能为空") ClaimMember> members) {
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

    /** 行李响应：含完整事件轨迹。 */
    public record BagResponse(String bagTag, String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, String groupKey, List<ItineraryItem> itinerary,
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

    /** 建组响应：返回固定成员集合与共同行程。 */
    public record FormGroupResponse(String groupKey, String status, String finalLegId,
                                    String finalStation, List<String> members,
                                    List<ItineraryItem> itinerary) {
    }

    /** 组查询中的逐成员状态项；notReadyReason 为该成员未就绪原因，已就绪为 null。 */
    public record GroupMemberStatus(String bagTag, String status, int version,
                                    String currentLocation, int nextLegIndex,
                                    String shortLegId, String shortDestination,
                                    String notReadyReason) {
    }

    /** 组查询响应：逐成员状态与整组未齐原因；查询不触发认领。 */
    public record GroupResponse(String groupKey, String status, String finalLegId,
                                String finalStation, boolean allReady,
                                String notReadyReason, List<GroupMemberStatus> members) {
    }

    /** 认领响应：成功后各成员均为 DELIVERED，claimedMembers 为冻结的完整成员集合。 */
    public record ClaimGroupResponse(String groupKey, String status,
                                     List<String> claimedMembers, String claimedBy,
                                     String claimedAt) {
    }
}
