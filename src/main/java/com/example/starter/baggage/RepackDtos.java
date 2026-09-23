package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 行李容器与重封单 API 的请求与响应 DTO。
 * 源容器须同一航段、同一交接点且状态 SEALED；目标分区必须是源清单的精确分区。
 */
public final class RepackDtos {

    private RepackDtos() {
    }

    /** 封装容器请求：把已装载到同一航段的行李封入一个新的 SEALED 容器，1~200 件不重复。 */
    public record ContainerPackRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "containerNo 不能为空") String containerNo,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "handoverPoint 不能为空") String handoverPoint,
            @NotBlank(message = "sealNo 不能为空") String sealNo,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 200, message = "单容器行李数量必须为 1~200")
            List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 重封源容器项：提交当前 expectedVersion 与旧封签号做激活核对。 */
    public record RepackSourceRequest(
            @NotBlank(message = "源容器 containerNo 不能为空") String containerNo,
            @NotNull(message = "源容器 expectedVersion 不能为空") Integer expectedVersion,
            @NotBlank(message = "源容器旧 sealNo 不能为空") String sealNo) {
    }

    /** 重封目标容器项：全局唯一的新容器编号、新封签号与完整 bagTag 集合。 */
    public record RepackTargetRequest(
            @NotBlank(message = "目标容器 containerNo 不能为空") String containerNo,
            @NotBlank(message = "目标容器 newSealNo 不能为空") String newSealNo,
            @NotNull(message = "目标 bagTags 不能为空")
            @Size(min = 1, max = 200, message = "单目标容器行李数量必须为 1~200")
            List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 创建重封单请求：1~10 个源容器、1~10 个目标容器，全部行李合计 2~200 件。 */
    public record CreateRepackRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "repackKey 不能为空") String repackKey,
            @NotNull(message = "sources 不能为空")
            @Size(min = 1, max = 10, message = "源容器数量必须为 1~10")
            List<@Valid RepackSourceRequest> sources,
            @NotNull(message = "targets 不能为空")
            @Size(min = 1, max = 10, message = "目标容器数量必须为 1~10")
            List<@Valid RepackTargetRequest> targets) {
    }

    /** 激活重封单请求：操作人与复核人必须为两名不同人员。 */
    public record ActivateRepackRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "operatorId 不能为空") String operatorId,
            @NotBlank(message = "reviewerId 不能为空") String reviewerId) {
    }

    /** 容器响应：含当前绑定的完整 bagTag 集合（排序）。 */
    public record ContainerResponse(String containerNo, String legId, String handoverPoint,
                                    String sealNo, String status, int version, List<String> bagTags) {
    }

    /** 预览中的源容器快照。 */
    public record RepackSourceView(String containerNo, int expectedVersion, String oldSealNo,
                                   String status, List<String> bagTags) {
    }

    /** 预览中的目标容器视图：fromSources 标明每件行李来自哪个源容器。 */
    public record RepackTargetView(String containerNo, String newSealNo, List<String> bagTags,
                                   List<TargetSourcePart> fromSources) {
    }

    /** 目标容器中来自单个源容器的行李分组。 */
    public record TargetSourcePart(String sourceContainerNo, List<String> bagTags) {
    }

    /** 单件行李的清单移动差异：源容器 -> 目标容器。 */
    public record RepackMovement(String bagTag, String sourceContainerNo, String targetContainerNo) {
    }

    /** 单件行李创建时的扫描状态快照。 */
    public record BagScanStatus(String bagTag, String scanStatus, String loadedLegId,
                                String bagStatus, String currentLocation) {
    }

    /** 创建重封单的预览响应：只含清单差异与扫描状态，不做任何移动。 */
    public record RepackPreviewResponse(String repackKey, String status, String legId,
                                        String handoverPoint, List<RepackSourceView> sources,
                                        List<RepackTargetView> targets, int totalBags,
                                        List<RepackMovement> differences,
                                        List<BagScanStatus> scanStatuses) {
    }

    /** 重封单中的容器侧快照（证据/详情查询用）。 */
    public record RepackContainerSnapshot(String containerNo, String sealNo, String status,
                                          Integer version, List<String> bagTags) {
    }

    /** 证据明细行。 */
    public record RepackEvidenceEntry(String bagTag, String sourceContainerNo, String targetContainerNo,
                                      String oldSealNo, String newSealNo) {
    }

    /** 重封单详情响应：PREVIEW 时 operator/激活快照为空，ACTIVE 时含前后清单与双人信息。 */
    public record RepackDetailResponse(String repackKey, String status, String legId,
                                       String handoverPoint, String operatorId, String reviewerId,
                                       List<RepackContainerSnapshot> sources,
                                       List<RepackContainerSnapshot> targets,
                                       List<RepackMovement> partition,
                                       String createdAt, String activatedAt) {
    }

    /** 激活成功响应：源容器统一 CLOSED_REPACKED，目标容器统一 SEALED。 */
    public record RepackActivatedResponse(String repackKey, String status, String operatorId,
                                          String reviewerId, List<RepackContainerSnapshot> sources,
                                          List<RepackContainerSnapshot> targets,
                                          List<RepackEvidenceEntry> evidence, String activatedAt) {
    }

    /** 证据查询响应：只读、明细按 bagTag 稳定排序。 */
    public record RepackEvidenceResponse(String repackKey, String status, String legId,
                                         String handoverPoint, String operatorId, String reviewerId,
                                         List<RepackEvidenceEntry> entries) {
    }
}
