package com.example.starter.baggage;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 行李容器与容器重封单 API 的请求与响应 DTO。
 */
public final class ContainerDtos {

    private ContainerDtos() {
    }

    /** 创建容器请求：容器绑定同一航段与同一交接点。 */
    public record CreateContainerRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "containerId 不能为空") String containerId,
            @NotBlank(message = "legId 不能为空") String legId,
            @NotBlank(message = "handoverPoint 不能为空") String handoverPoint) {
    }

    /** 容器装箱请求：1~200 个不重复 bagTag，整批原子。 */
    public record ContainerLoadRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 200, message = "单次装箱数量必须为 1~200")
            List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 容器封签请求：sealNo 全局唯一。 */
    public record ContainerSealRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotBlank(message = "sealNo 不能为空") String sealNo) {
    }

    /** 容器响应：bags 为当前清单（按袋号排序）。 */
    public record ContainerResponse(String containerId, String legId, String handoverPoint,
                                    String status, int version, String sealNo, List<String> bags) {
    }

    /** 重封单源容器项：提交期望版本与旧封签。 */
    public record ResealSourceRequest(
            @NotBlank(message = "containerId 不能为空") String containerId,
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotBlank(message = "sealNo 不能为空") String sealNo) {
    }

    /** 重封单目标容器项：containerId 与 newSealNo 全局唯一，bagTags 非空。 */
    public record ResealTargetRequest(
            @NotBlank(message = "containerId 不能为空") String containerId,
            @NotBlank(message = "newSealNo 不能为空") String newSealNo,
            @NotNull(message = "bagTags 不能为空")
            @Size(min = 1, max = 200, message = "单个目标容器袋号数必须为 1~200")
            List<@NotBlank(message = "bagTag 不能为空") String> bagTags) {
    }

    /** 创建重封单请求：1~10 个源容器、1~10 个目标容器，操作人与复核人须不同。 */
    public record CreateResealOrderRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "repackKey 不能为空") String repackKey,
            @NotBlank(message = "operatorId 不能为空") String operatorId,
            @NotBlank(message = "reviewerId 不能为空") String reviewerId,
            @NotNull(message = "sources 不能为空")
            @Size(min = 1, max = 10, message = "源容器数量必须为 1~10")
            List<@Valid ResealSourceRequest> sources,
            @NotNull(message = "targets 不能为空")
            @Size(min = 1, max = 10, message = "目标容器数量必须为 1~10")
            List<@Valid ResealTargetRequest> targets) {
    }

    /** 重封单确认请求：confirmerId 须为操作人或复核人。 */
    public record ConfirmResealRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "confirmerId 不能为空") String confirmerId) {
    }

    /** 源容器当前清单视图（创建预览用）。 */
    public record SourceManifestView(String containerId, String status, int version,
                                     String sealNo, List<String> bagTags) {
    }

    /** 行李当前扫描状态（创建预览用）。 */
    public record BagScanState(String bagTag, String status, String loadedLegId,
                               String currentLocation) {
    }

    /** 创建时预览：源清单、目标相对源清单的缺失/外部袋号差异及各行李当前扫描状态。 */
    public record ResealPreview(List<SourceManifestView> sourceManifests,
                                List<String> missing, List<String> external,
                                List<BagScanState> bagStates) {
    }

    /** 清单快照项：容器号 + 封签 + 袋号清单（袋号排序）。 */
    public record ResealSnapshotItem(String containerId, String sealNo, List<String> bagTags) {
    }

    /** 重封单源容器视图（稳定排序）。 */
    public record ResealSourceView(String containerId, int expectedVersion, String sealNo) {
    }

    /** 重封单目标容器视图（稳定排序）。 */
    public record ResealTargetView(String containerId, String newSealNo, List<String> bagTags) {
    }

    /** 重封单响应/证据视图：所有集合稳定排序；preview 仅创建时返回。 */
    public record ResealOrderResponse(String repackKey, String status, String legId,
                                      String handoverPoint, String operatorId, String reviewerId,
                                      boolean operatorConfirmed, boolean reviewerConfirmed,
                                      List<ResealSourceView> sources, List<ResealTargetView> targets,
                                      ResealPreview preview,
                                      List<ResealSnapshotItem> beforeSnapshot,
                                      List<ResealSnapshotItem> afterSnapshot,
                                      String activatedAt) {
    }

    /** 行李容器链项。 */
    public record ContainerChainItem(int seq, String containerId, String enteredAt) {
    }

    /** 行李容器链响应：逐项保留行李经过的全部容器。 */
    public record ContainerChainResponse(String bagTag, List<ContainerChainItem> chain) {
    }
}
