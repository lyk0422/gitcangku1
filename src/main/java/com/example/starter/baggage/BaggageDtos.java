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

    /** 海关暂扣请求：对未到达最终目的地且不在 SEALED 航段上的行李生效。 */
    public record CustomsHoldRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "holdKey 不能为空") String holdKey,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "holdLocation 不能为空") String holdLocation,
            @NotBlank(message = "reason 不能为空") String reason) {
    }

    /** 海关暂扣解除确认请求：两名不同操作人按同一 holdKey 各确认一次。 */
    public record CustomsHoldConfirmRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "holdKey 不能为空") String holdKey,
            @NotBlank(message = "operatorId 不能为空") String operatorId) {
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
                              String status, String activeHoldKey, String loadedLegId,
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

    /** 海关暂扣生效响应：confirmations 为当前确认人数，暂扣成功时为 0。 */
    public record CustomsHoldResponse(String holdKey, String bagTag, String holdLocation,
                                      String reason, String status, int confirmations,
                                      String previousStatus, String heldAt) {
    }

    /** 暂扣解除确认人项。 */
    public record HoldOperatorConfirmation(String operatorId, String confirmedAt) {
    }

    /** 解除记录：第二次确认时固化两名操作人及时刻，未解除为 null 字段。 */
    public record HoldReleaseView(String firstOperatorId, String firstConfirmedAt,
                                  String secondOperatorId, String secondConfirmedAt,
                                  String releasedAt) {
    }

    /** 解除确认提交响应：status 为 ACTIVE（仍待第二人）或 RELEASED（已解除）。 */
    public record CustomsHoldConfirmResponse(String holdKey, String bagTag, String status,
                                             int confirmations,
                                             List<HoldOperatorConfirmation> operators,
                                             HoldReleaseView release) {
    }

    /** 暂扣历史项：不可变暂扣信息、确认人与解除记录。 */
    public record HoldHistoryItem(String holdKey, String bagTag, String holdLocation,
                                  String reason, String status, String heldAt,
                                  List<HoldOperatorConfirmation> confirmations,
                                  HoldReleaseView release) {
    }

    /** 行李暂扣历史响应。 */
    public record HoldHistoryResponse(String bagTag, List<HoldHistoryItem> holds) {
    }

    /** 待第二人确认清单项：当前仅一人确认、暂扣仍 ACTIVE。 */
    public record PendingHoldItem(String holdKey, String bagTag, String holdLocation,
                                  String reason, String heldAt,
                                  String firstOperatorId, String firstConfirmedAt) {
    }

    /** 待第二人确认清单响应。 */
    public record PendingHoldListResponse(List<PendingHoldItem> pending) {
    }

    /** 当前交接阻断原因项：reasonType 标识阻断来源（CUSTOMS_HOLD/SHORT_UNLOADED/LOADED/DELIVERED 等）。 */
    public record BlockingReason(String reasonType, String message,
                                 String holdKey, String holdLocation) {
    }

    /** 行李当前交接阻断原因响应：blocked 为 true 时 reasons 非空。 */
    public record BagBlockingResponse(String bagTag, String status, boolean blocked,
                                      List<BlockingReason> reasons) {
    }
}
