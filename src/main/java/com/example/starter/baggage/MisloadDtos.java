package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 错装行李批次追回与剩余路径原子改派 API 的请求与响应 DTO。
 */
public final class MisloadDtos {

    private MisloadDtos() {
    }

    /** 错装逐件登记项：提交每件行李当前版本与扫描站点。 */
    public record MisloadBagItem(
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotBlank(message = "scanStation 不能为空") String scanStation) {
    }

    /** 错装批次登记请求：2~50 件行李在同一实际航段到达。 */
    public record RegisterMisloadRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "incidentKey 不能为空") String incidentKey,
            @NotBlank(message = "actualLegId 不能为空") String actualLegId,
            @NotNull(message = "bags 不能为空")
            @Size(min = 2, max = 50, message = "错装批次件数必须为 2~50")
            List<@Valid MisloadBagItem> bags) {
    }

    /** 错装批次登记响应。 */
    public record RegisterMisloadResponse(String incidentKey, String actualLegId,
                                          String arrivalStation, String status,
                                          List<String> bagTags) {
    }

    /** 单件行李恢复路径提交：从当前站到原最终目的地的 1~5 段有序路径。 */
    public record RerouteBagRequest(
            @NotBlank(message = "bagTag 不能为空") String bagTag,
            @NotNull(message = "path 不能为空")
            @Size(min = 1, max = 5, message = "恢复路径段数必须为 1~5")
            List<@NotBlank(message = "恢复路径航段不能为空") String> path) {
    }

    /** 恢复路径预览请求：逐件提交恢复路径，预览冻结版本与路径快照。 */
    public record PreviewRerouteRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "bags 不能为空")
            @Size(min = 1, max = 50, message = "逐件路径数量必须为 1~50")
            List<@Valid RerouteBagRequest> bags) {
    }

    /** 预览中单件冻结视图：原剩余路径与恢复路径并列。 */
    public record ReroutePreviewItem(String bagTag, int bagVersion, String currentStation,
                                     String finalDestination, List<String> originalRemaining,
                                     List<String> recoveryPath) {
    }

    /** 恢复路径预览响应。 */
    public record PreviewRerouteResponse(String incidentKey, String status,
                                         List<ReroutePreviewItem> bags) {
    }

    /** 原子改派确认请求：重新校验全部行李状态与路径。 */
    public record ConfirmRerouteRequest(
            @NotBlank(message = "requestId 不能为空") String requestId) {
    }

    /** 确认后单件改派结果视图。 */
    public record RerouteResultItem(String bagTag, int newVersion, int pathGeneration,
                                    String currentLocation, String status,
                                    List<String> newRemainingPath) {
    }

    /** 原子改派确认响应：事件关闭与逐件新剩余路径。 */
    public record ConfirmRerouteResponse(String incidentKey, String status,
                                         List<RerouteResultItem> bags) {
    }

    /** 逐件路径血缘快照项。 */
    public record PathSnapshotItem(String pathKind, int generation, int seq,
                                   String legId, String origin, String destination,
                                   String createdAt) {
    }

    /** 错装批次逐件视图。 */
    public record MisloadItemResponse(String bagTag, int bagVersion, String scanStation,
                                      String createdAt) {
    }

    /** 错装批次查询响应：批次头、逐件登记与最新预览冻结（未预览为空）。 */
    public record MisloadIncidentResponse(String incidentKey, String actualLegId,
                                          String arrivalStation, String status,
                                          String createdAt, String closedAt,
                                          List<MisloadItemResponse> items,
                                          List<ReroutePreviewItem> preview) {
    }

    /** 逐件路径血缘查询响应：只追加的原/新路径不可变快照，按代次与顺序排列。 */
    public record BagPathLineageResponse(String bagTag, int pathGeneration,
                                         List<PathSnapshotItem> snapshots) {
    }
}
