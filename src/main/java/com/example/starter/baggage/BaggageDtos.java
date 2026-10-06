package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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

    /** 登记行李请求：legIds 为 1~5 个有序航段；重量档案可选，须成对提供。 */
    public record RegisterBagRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "legIds 不能为空")
            @Size(min = 1, max = 5, message = "行程航段数必须为 1~5") List<@NotBlank(message = "航段不能为空") String> legIds,
            @Min(value = 1, message = "自重必须为正整数克") Integer selfWeightGrams,
            @Min(value = 0, message = "可承上方重量必须为非负整数克") Integer maxLoadAboveGrams) {
        /** 不带重量档案的登记（档案可稍后通过重量修改建立）。 */
        public RegisterBagRequest(String requestId, String bagTag, List<String> legIds) {
            this(requestId, bagTag, legIds, null, null);
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

    /** 堆栈声明条目：一件行李在舱内的垂直堆叠位置。 */
    public record StackEntryInput(
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "stackNo 不能为空")
            @Min(value = 1, message = "堆栈号必须为正整数") Integer stackNo,
            @NotNull(message = "layer 不能为空")
            @Min(value = 1, message = "层号必须为从 1 起的正整数") Integer layer) {
    }

    /**
     * 堆栈声明请求：以完整后态声明舱内全部装载行李的堆叠布局，
     * 相对当前清单的新增即装载、缺失即卸载、位置变化即换层，整次原子生效。
     */
    public record DeclareStackRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "entries 不能为空")
            @Size(max = 100, message = "单次堆栈声明条目数不能超过 100") List<@Valid StackEntryInput> entries) {
    }

    /** 重量档案修改请求：expectedVersion 对无档案行李为 0，否则为当前档案版本。 */
    public record UpdateWeightRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operator 不能为空") String operator,
            @NotNull(message = "expectedVersion 不能为空")
            @Min(value = 0, message = "expectedVersion 不能为负") Integer expectedVersion,
            @NotNull(message = "selfWeightGrams 不能为空")
            @Min(value = 1, message = "自重必须为正整数克") Integer selfWeightGrams,
            @NotNull(message = "maxLoadAboveGrams 不能为空")
            @Min(value = 0, message = "可承上方重量必须为非负整数克") Integer maxLoadAboveGrams) {
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

    /** 行李响应：含完整事件轨迹与重量档案（无档案时重量字段为 null）。 */
    public record BagResponse(String bagTag, String currentLocation, int nextLegIndex,
                              String status, String loadedLegId, List<ItineraryItem> itinerary,
                              String shortLegId, String shortDestination, String shortRegisteredAt,
                              Integer selfWeightGrams, Integer maxLoadAboveGrams, Integer weightVersion,
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

    /** 堆栈布局条目视图：含封舱冻结时点的自重与上方载荷（克）。 */
    public record StackEntryView(String bagTag, int stackNo, int layer,
                                 int selfWeightGrams, int loadAboveGrams) {
    }

    /** 堆栈声明响应：entries 按堆栈号、层号升序，为生效后的完整布局。 */
    public record DeclareStackResponse(String legId, String status, int version,
                                       List<StackEntryView> entries) {
    }

    /** 堆栈布局查询响应：OPEN 航段为当前布局，SEALED/ARRIVED 为封舱冻结快照。 */
    public record StackLayoutResponse(String legId, String status, int version,
                                      List<StackEntryView> entries) {
    }

    /** 重量档案响应。 */
    public record WeightResponse(String bagTag, int selfWeightGrams,
                                 int maxLoadAboveGrams, int weightVersion) {
    }
}
