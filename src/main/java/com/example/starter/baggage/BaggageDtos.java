package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 联程行李装载交接 API 的请求与响应 DTO。
 * 尺寸单位均为毫米、坐标原点为舱底左下角；二维字段仅配置平面舱位的航段使用。
 */
public final class BaggageDtos {

    private BaggageDtos() {
    }

    /**
     * 登记航段请求。
     * floorWidthMm/floorHeightMm 非空时为该航段固定矩形舱底长宽（正整数毫米），为空表示无二维配置的旧航段。
     */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination,
            Integer floorWidthMm,
            Integer floorHeightMm,
            String operator) {

        /** 无二维平面舱位的旧航段登记入口。 */
        public RegisterLegRequest(String requestId, String legId, String origin, String destination) {
            this(requestId, legId, origin, destination, null, null, null);
        }
    }

    /**
     * 登记行李请求：legIds 为 1~5 个有序航段。
     * widthMm/heightMm 为登记长宽（正整数毫米）；装载到平面舱位航段时必填，旧航段可为空。
     */
    public record RegisterBagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            Integer widthMm,
            Integer heightMm,
            @NotNull(message = "legIds 不能为空")
            @Size(min = 1, max = 5, message = "行程航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> legIds,
            String operator) {

        /** 无尺寸登记的旧入口（只能装载无二维配置的航段）。 */
        public RegisterBagRequest(String requestId, String bagTag, List<String> legIds) {
            this(requestId, bagTag, null, null, legIds, null);
        }
    }

    /** 批量装载请求：1~20 个不重复 bagTag；平面舱位航段须随附同序位置列表。 */
    public record LoadRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 20, message = "批量装载数量必须为 1~20") List<@NotBlank(message = "bagTag 不能为空") String> bagTags,
            @Size(max = 20, message = "位置数量必须与装载数量一致") List<@Valid PositionRequest> positions,
            String operator) {

        /** 无二维配置旧航段的装载入口。 */
        public LoadRequest(String requestId, Integer expectedVersion, List<String> bagTags) {
            this(requestId, expectedVersion, bagTags, null, null);
        }
    }

    /**
     * 整批重排请求：未封舱时统一移动 1~10 件已装行李。
     * 先整体移除这些旧位置，再统一校验新位置与未移动行李，合法互换不得被逐件拒绝。
     */
    public record RearrangeRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "moves 不能为空")
            @Size(min = 1, max = 10, message = "整批重排数量必须为 1~10") List<@Valid PositionRequest> moves,
            String operator) {
    }

    /** 单件行李落位/移动：rotated 为是否相对登记尺寸旋转 90 度，x/y 为左下角坐标（毫米，非负）。 */
    public record PositionRequest(
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "rotated 不能为空") Boolean rotated,
            @NotNull(message = "x 不能为空") Integer x,
            @NotNull(message = "y 不能为空") Integer y) {
    }

    /** 封舱请求。 */
    public record SealRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            String operator) {

        public SealRequest(String requestId, Integer expectedVersion) {
            this(requestId, expectedVersion, null);
        }
    }

    /** 到达确认请求：实际袋号集合，须与封舱清单完全一致。 */
    public record ArriveRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "bagTags 不能为空") List<@NotBlank(message = "bagTag 不能为空") String> bagTags,
            String operator) {

        public ArriveRequest(String requestId, List<String> bagTags) {
            this(requestId, bagTags, null);
        }
    }

    /** 差异到达请求：实际袋号集合须为封舱清单子集（允许空集），expectedVersion 做并发版本校验。 */
    public record DifferenceArriveRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "bagTags 不能为空") List<@NotBlank(message = "bagTag 不能为空") String> bagTags,
            String operator) {
    }

    /** 补到请求：短卸行李在缺失航段的应到站实际到达。 */
    public record RecoverRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "missingLegId 不能为空") String missingLegId,
            @NotBlank(message = "actualStation 不能为空") String actualStation,
            String operator) {

        public RecoverRequest(String requestId, String bagTag, String missingLegId, String actualStation) {
            this(requestId, bagTag, missingLegId, actualStation, null);
        }
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
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
                              String shortLegId, String shortDestination, String shortRegisteredAt,
                              List<TraceEvent> events) {
    }

    /**
     * 落位矩形响应：widthMm/heightMm 为登记尺寸，rotated 表示落位是否旋转 90 度，
     * x/y 为左下角坐标（毫米）。无二维配置航段不出现在 placements 中。
     */
    public record PlacementResponse(String bagTag, int widthMm, int heightMm,
                                    boolean rotated, int x, int y) {
    }

    /** 批量装载响应：placements 为整批落位（仅平面舱位航段返回）。 */
    public record LoadResponse(String legId, String status, int version, List<String> loaded,
                               List<PlacementResponse> placements) {

        public LoadResponse(String legId, String status, int version, List<String> loaded) {
            this(legId, status, version, loaded, null);
        }
    }

    /** 整批重排响应：placements 为重排后舱内全部落位。 */
    public record RearrangeResponse(String legId, String status, int version,
                                    List<PlacementResponse> placements) {
    }

    /** 封舱响应：manifest 为只读袋号清单快照，placements 为冻结的完整二维身份快照。 */
    public record SealResponse(String legId, String status, int version, List<String> manifest,
                               List<PlacementResponse> placements) {

        public SealResponse(String legId, String status, int version, List<String> manifest) {
            this(legId, status, version, manifest, null);
        }
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

    /** 封舱清单查询响应：placements 为封舱冻结快照，floor 尺寸仅平面舱位航段非空。 */
    public record ManifestResponse(String legId, String status, int version, List<String> manifest,
                                   List<PlacementResponse> placements,
                                   Integer floorWidthMm, Integer floorHeightMm) {

        public ManifestResponse(String legId, String status, int version, List<String> manifest) {
            this(legId, status, version, manifest, null, null, null);
        }
    }

    /** 当前平面布局查询响应：placements 为未封舱时的当前落位，封舱后与冻结快照一致。 */
    public record LayoutResponse(String legId, String status, int version,
                                 Integer floorWidthMm, Integer floorHeightMm,
                                 List<PlacementResponse> placements) {
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
