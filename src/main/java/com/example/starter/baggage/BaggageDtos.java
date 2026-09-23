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

    /** 登记航段请求。departureTime 为可选 UTC 时刻（ISO-8601），错装恢复路径段必须已登记时刻。 */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination,
            String departureTime) {

        /** 兼容不登记出发时刻的调用（历史接口与测试）。 */
        public RegisterLegRequest(String requestId, String legId, String origin, String destination) {
            this(requestId, legId, origin, destination, null);
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
                              String status, int version, int pathGeneration, String loadedLegId,
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

    /** 错装登记单件：提交行李当前版本与该件扫描站点。 */
    public record MisloadBagItem(
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotBlank(message = "scanStation 不能为空") String scanStation) {
    }

    /** 错装批次登记请求：2~50 件不重复行李，在同一实际航段到达但该航段不属于各自行程。 */
    public record MisloadRegisterRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "incidentKey 不能为空") String incidentKey,
            @NotBlank(message = "actualLegId 不能为空") String actualLegId,
            @NotNull(message = "bags 不能为空")
            @Size(min = 2, max = 50, message = "错装批次件数必须为 2~50") List<@Valid MisloadBagItem> bags) {
    }

    /** 错装恢复路径段：引用已登记出发时刻的航段。 */
    public record RecoverySegment(
            @NotBlank(message = "legId 不能为空") String legId) {
    }

    /** 错装预览单件恢复路径：1~5 段，段顺序有意义。 */
    public record MisloadRecoveryItem(
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "segments 不能为空")
            @Size(min = 1, max = 5, message = "恢复路径段数必须为 1~5") List<@Valid RecoverySegment> segments) {
    }

    /** 错装恢复路径预览请求：逐件提交从当前站到原最终目的地的 1~5 段恢复路径。 */
    public record MisloadPreviewRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "items 不能为空")
            @Size(min = 1, max = 50, message = "恢复路径明细数必须为 1~50") List<@Valid MisloadRecoveryItem> items) {
    }

    /** 路径段视图：行程/恢复路径/血缘快照统一结构，departureTime 未登记时刻时为 null。 */
    public record PathSegmentView(int seq, String legId, String origin, String destination,
                                  String departureTime) {
    }

    /** 错装逐件冻结视图：行李版本、原剩余路径与建议恢复路径。 */
    public record MisloadItemView(String bagTag, int frozenVersion, String scanStation,
                                  int pathGeneration, String currentStation,
                                  String finalDestination, List<PathSegmentView> originalRemaining,
                                  List<PathSegmentView> recoveryPath) {
    }

    /** 错装批次登记响应：返回 OPEN 批次与逐件冻结的原剩余路径。 */
    public record MisloadRegisterResponse(String incidentKey, String actualLegId, String scanStation,
                                          String status, String registeredAt, List<MisloadItemView> items) {
    }

    /** 错装批次预览响应：冻结行李版本、原剩余路径及恢复路径（未提交恢复路径的件 recoveryPath 为空）。 */
    public record MisloadPreviewResponse(String incidentKey, String status,
                                         List<MisloadItemView> items) {
    }

    /** 错装改派确认请求：按预览冻结内容原子确认；body 可只传 requestId。 */
    public record MisloadConfirmRequest(
            @NotBlank(message = "requestId 不能为空") String requestId) {
    }

    /** 错装改派确认响应：事件关闭并给出各行李新代次。 */
    public record MisloadConfirmResponse(String incidentKey, String status, int generation,
                                         String confirmedAt, List<ConfirmedBagView> bags) {
    }

    /** 改派后逐件结果。 */
    public record ConfirmedBagView(String bagTag, String status, String currentLocation,
                                   int pathGeneration, int nextLegIndex,
                                   List<PathSegmentView> newPath) {
    }

    /** 路径血缘快照视图。 */
    public record PathSnapshotView(String bagTag, String kind, int generation,
                                   List<PathSegmentView> path) {
    }

    /** 错装批次查询响应：批次头、逐件冻结与不可变路径血缘（CONFIRMED 后含原/新快照）。 */
    public record MisloadIncidentResponse(String incidentKey, String actualLegId, String scanStation,
                                          String status, int generation,
                                          String registeredAt, String confirmedAt,
                                          List<MisloadItemView> items,
                                          List<PathSnapshotView> snapshots) {
    }
}
