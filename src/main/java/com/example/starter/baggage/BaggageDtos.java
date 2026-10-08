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

    /**
     * 登记航段请求。floorLengthMm/floorWidthMm 同时提供表示启用矩形舱底布局（毫米，正整数）；
     * 同时缺省表示无二维舱位布局的旧航段，装载不要求位置。
     */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination,
            Integer floorLengthMm,
            Integer floorWidthMm) {

        /** 兼容旧调用：无舱位布局。 */
        public RegisterLegRequest(String requestId, String legId, String origin, String destination) {
            this(requestId, legId, origin, destination, null, null);
        }
    }

    /**
     * 登记行李请求：legIds 为 1~5 个有序航段。
     * lengthMm/widthMm 同时提供表示行李固定尺寸（毫米，正整数）；同时缺省表示未登记尺寸，
     * 未登记尺寸的行李不能装入启用布局的航段。
     */
    public record RegisterBagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "legIds 不能为空")
            @Size(min = 1, max = 5, message = "行程航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> legIds,
            Integer lengthMm,
            Integer widthMm) {

        /** 兼容旧调用：未登记尺寸。 */
        public RegisterBagRequest(String requestId, String bagTag, List<String> legIds) {
            this(requestId, bagTag, legIds, null, null);
        }
    }

    /** 落位提交：袋号、左下角坐标（毫米，非负）与旋转角度（仅 0 或 90 度）。 */
    public record PlacementRequest(
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "xMm 不能为空") Integer xMm,
            @NotNull(message = "yMm 不能为空") Integer yMm,
            @NotNull(message = "rotation 不能为空") Integer rotation) {
    }

    /**
     * 批量装载请求：1~20 个不重复 bagTag。
     * 航段启用舱位布局时 placements 必须与 bagTags 一一对应；旧航段不得提交 placements。
     */
    public record LoadRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 20, message = "批量装载数量必须为 1~20") List<@NotBlank(message = "bagTag 不能为空") String> bagTags,
            String operator,
            List<@Valid PlacementRequest> placements) {

        /** 兼容旧调用：无操作者、无位置。 */
        public LoadRequest(String requestId, Integer expectedVersion, List<String> bagTags) {
            this(requestId, expectedVersion, bagTags, null, null);
        }
    }

    /** 整批重排请求：未封舱时对 1~10 件已装行李统一下发新位置，操作者非空。 */
    public record RearrangeRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "placements 不能为空")
            @Size(min = 1, max = 10, message = "整批重排数量必须为 1~10") List<@Valid PlacementRequest> placements) {
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
    /** 冻结落位视图：袋号、尺寸（毫米）、旋转角度与左下角坐标（毫米）；无二维配置时尺寸/旋转/坐标为 NULL。 */
    public record PlacementView(String bagTag, Integer lengthMm, Integer widthMm,
                                Integer rotation, Integer xMm, Integer yMm) {
    }

    /** 航段响应：floorLengthMm/floorWidthMm 为舱底长宽（毫米），无布局为 NULL。 */
    public record LegResponse(String legId, String origin, String destination,
                              String status, int version, Integer floorLengthMm, Integer floorWidthMm) {
    }

    /** 行程明细项。 */
    public record ItineraryItem(int seq, String legId, String origin, String destination) {
    }

    /** 行李轨迹事件。 */
    public record TraceEvent(int seq, String eventType, String legId,
                             String location, String eventTime) {
    }

    /** 行李响应：含完整事件轨迹与固定尺寸（毫米，未登记为 NULL）。 */
    public record BagResponse(String bagTag, String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
                              String shortLegId, String shortDestination, String shortRegisteredAt,
                              List<TraceEvent> events, Integer lengthMm, Integer widthMm) {
    }

    /** 批量装载响应：placements 为本批实际落位（旧航段为空列表）。 */
    public record LoadResponse(String legId, String status, int version, List<String> loaded,
                               List<PlacementView> placements) {
    }

    /** 整批重排响应：rearranged 为重排袋号，placements 为重排后落位。 */
    public record RearrangeResponse(String legId, String status, int version, List<String> rearranged,
                                    List<PlacementView> placements) {
    }

    /** 封舱响应：manifest 为只读装载清单快照，placements 为冻结布局（无布局为空列表）。 */
    public record SealResponse(String legId, String status, int version, List<String> manifest,
                               List<PlacementView> placements) {
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

    /** 封舱清单查询响应：placements 为封舱冻结布局（未封舱或无布局为空列表）。 */
    public record ManifestResponse(String legId, String status, int version, List<String> manifest,
                                   List<PlacementView> placements) {
    }

    /** 航段差异快照响应：arrivalType 为 EXACT/DIFF，actual 仅差异到达时有值。 */
    public record LegDifferenceResponse(String legId, String status, int version,
                                        List<String> manifest, String arrivalType, List<String> actual,
                                        List<PlacementView> placements) {
    }

    /** 未补到行李清单项。 */
    public record ShortItem(String bagTag, String missingLegId, String expectedStation,
                            String registeredAt, int nextLegIndex, String currentLocation) {
    }

    /** 未补到清单响应。 */
    public record ShortListResponse(List<ShortItem> shortUnloaded) {
    }
}
