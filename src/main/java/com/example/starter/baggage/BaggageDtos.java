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

    /** 登记航段请求：legType 缺省为 LEGACY 普通旧航段，MERKLE 为新类型（封舱冻结清单根）。 */
    public record RegisterLegRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "origin 不能为空") String origin,
            @NotBlank(message = "destination 不能为空") String destination,
            String legType) {
        /** 兼容旧调用：缺省 LEGACY。 */
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

    /** 封舱请求：新类型航段必须携带非空 operator。 */
    public record SealRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            String operator) {
        /** 兼容旧调用：无操作者。 */
        public SealRequest(String requestId, Integer expectedVersion) {
            this(requestId, expectedVersion, null);
        }
    }

    /** 到达确认请求：实际袋号集合，须与封舱清单完全一致。 */
    public record ArriveRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "bagTags 不能为空") List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 差异到达请求：实际袋号集合须为封舱清单子集（允许空集），expectedVersion 做并发版本校验；
     *  新类型航段必须携带非空 operator。 */
    public record DifferenceArriveRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "bagTags 不能为空") List<@NotBlank(message = "bagTag 不能为空") String> bagTags,
            String operator) {
        /** 兼容旧调用：无操作者。 */
        public DifferenceArriveRequest(String requestId, Integer expectedVersion, List<String> bagTags) {
            this(requestId, expectedVersion, bagTags, null);
        }
    }

    /** 成员证明节点：逐层兄弟摘要及其方向（LEFT 兄弟在左 / RIGHT 兄弟在右）。 */
    public record ProofNode(
            @NotBlank(message = "证明节点方向不能为空") String position,
            @NotBlank(message = "证明节点摘要不能为空") String hash) {
    }

    /** 补到请求：短卸行李在缺失航段的应到站实际到达；
     *  缺失航段为新类型时必须携带非空 operator 及清单根、叶下标、成员证明。 */
    public record RecoverRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotBlank(message = "missingLegId 不能为空") String missingLegId,
            @NotBlank(message = "actualStation 不能为空") String actualStation,
            String operator,
            String manifestRoot,
            Integer leafIndex,
            List<@Valid ProofNode> proof) {
        /** 兼容旧调用：普通旧航段补到无需证明。 */
        public RecoverRequest(String requestId, String bagTag, String missingLegId, String actualStation) {
            this(requestId, bagTag, missingLegId, actualStation, null, null, null, null);
        }
    }

    /** 航段响应。 */
    public record LegResponse(String legId, String origin, String destination,
                              String status, int version, String legType) {
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

    /** 封舱响应：manifest 为只读装载清单快照；manifestRoot/leafCount 仅新类型航段有值。 */
    public record SealResponse(String legId, String status, int version, List<String> manifest,
                               String manifestRoot, Integer leafCount) {
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

    /** 封舱清单查询响应：manifestRoot/leafCount 仅新类型航段封舱后有值，冻结后不随补到或短卸变动。 */
    public record ManifestResponse(String legId, String status, int version, List<String> manifest,
                                   String legType, String manifestRoot, Integer leafCount) {
    }

    /** 成员证明查询响应：叶下标、叶数、清单根及逐层兄弟摘要。 */
    public record ProofResponse(String legId, String bagTag, int leafIndex, int leafCount,
                                String manifestRoot, List<ProofNode> proof) {
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
