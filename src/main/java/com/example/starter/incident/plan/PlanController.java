package com.example.starter.incident.plan;

import com.example.starter.incident.plan.dto.PlanRequests.DraftCreateRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftEdgeRemoveRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftEdgeRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftTaskRemoveRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftTaskRequest;
import com.example.starter.incident.plan.dto.PlanRequests.MergeRequest;
import com.example.starter.incident.plan.dto.PlanRequests.PlanCreateRequest;
import com.example.starter.incident.plan.dto.PlanRequests.PlanTaskActionRequest;
import com.example.starter.incident.plan.dto.PlanResponses.MergeEvidenceView;
import com.example.starter.incident.plan.dto.PlanResponses.MergeResultView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanDiffView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanTaskView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanVersionView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 处置方案版本与三方合并 REST API。写操作均要求 X-Actor-Id 请求头标识操作人
 * （须为事件当前指挥人）；差异与合并证据查询只读、稳定排序。
 */
@RestController
@RequestMapping("/api/incidents/{incidentKey}/plan")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    /**
     * 创建首个方案版本并直接发布（PUBLISHED 活动版本）。
     */
    @PostMapping("/versions")
    public ResponseEntity<PlanVersionView> createInitialPlan(@PathVariable String incidentKey,
                                                             @RequestHeader("X-Actor-Id") String actor,
                                                             @RequestBody PlanCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createInitialPlan(incidentKey, actor, req));
    }

    /**
     * 查询当前活动方案版本（含运行时执行状态，只读）。
     */
    @GetMapping
    public PlanVersionView getActivePlan(@PathVariable String incidentKey) {
        return service.getActivePlan(incidentKey);
    }

    /**
     * 查询指定方案版本（只读，稳定排序）。
     */
    @GetMapping("/versions/{versionNo}")
    public PlanVersionView getVersion(@PathVariable String incidentKey,
                                      @PathVariable int versionNo) {
        return service.getVersion(incidentKey, versionNo);
    }

    /**
     * 从 PUBLISHED 基版本创建 DRAFT 修订（branch 为 LEFT/RIGHT 标记）。
     */
    @PostMapping("/drafts")
    public ResponseEntity<PlanVersionView> createDraft(@PathVariable String incidentKey,
                                                       @RequestHeader("X-Actor-Id") String actor,
                                                       @RequestBody DraftCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createDraft(incidentKey, actor, req));
    }

    /**
     * 草稿任务新增/修改（按稳定 taskId upsert）。
     */
    @PostMapping("/drafts/{versionNo}/tasks")
    public PlanVersionView upsertDraftTask(@PathVariable String incidentKey,
                                           @PathVariable int versionNo,
                                           @RequestHeader("X-Actor-Id") String actor,
                                           @RequestBody DraftTaskRequest req) {
        return service.upsertDraftTask(incidentKey, versionNo, actor, req);
    }

    /**
     * 草稿任务移除（连同其本事件内部关联边）。
     */
    @PostMapping("/drafts/{versionNo}/tasks/{taskId}/remove")
    public PlanVersionView removeDraftTask(@PathVariable String incidentKey,
                                           @PathVariable int versionNo,
                                           @PathVariable String taskId,
                                           @RequestHeader("X-Actor-Id") String actor,
                                           @RequestBody DraftTaskRemoveRequest req) {
        return service.removeDraftTask(incidentKey, versionNo, taskId, actor, req);
    }

    /**
     * 草稿依赖边新增（toIncidentKey 为空表示本事件内部边）。
     */
    @PostMapping("/drafts/{versionNo}/edges")
    public PlanVersionView addDraftEdge(@PathVariable String incidentKey,
                                        @PathVariable int versionNo,
                                        @RequestHeader("X-Actor-Id") String actor,
                                        @RequestBody DraftEdgeRequest req) {
        return service.addDraftEdge(incidentKey, versionNo, actor, req);
    }

    /**
     * 草稿依赖边移除。
     */
    @PostMapping("/drafts/{versionNo}/edges/remove")
    public PlanVersionView removeDraftEdge(@PathVariable String incidentKey,
                                           @PathVariable int versionNo,
                                           @RequestHeader("X-Actor-Id") String actor,
                                           @RequestBody DraftEdgeRemoveRequest req) {
        return service.removeDraftEdge(incidentKey, versionNo, actor, req);
    }

    /**
     * 三方差异查询（只读、稳定排序）：自动采用的变更与显式冲突。
     */
    @GetMapping("/diff")
    public PlanDiffView diff(@PathVariable String incidentKey, @RequestParam int base,
                             @RequestParam int left, @RequestParam int right) {
        return service.diff(incidentKey, base, left, right);
    }

    /**
     * 三方合并并原子发布：成功只创建一个新的 PUBLISHED 版本，
     * 原分支标记 MERGED 保持不可变；requestId 幂等，mergeKey 唯一。
     */
    @PostMapping("/merges")
    public MergeResultView merge(@PathVariable String incidentKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody MergeRequest req) {
        return service.merge(incidentKey, actor, req);
    }

    /**
     * 合并证据查询（冻结 的三方差异、全部冲突解决、最终任务集与边集；只读）。
     */
    @GetMapping("/merges/{mergeKey}")
    public MergeEvidenceView getMergeEvidence(@PathVariable String incidentKey,
                                              @PathVariable String mergeKey) {
        return service.getMergeEvidence(incidentKey, mergeKey);
    }

    /**
     * 启动方案任务（仅 PENDING；全部前置任务 COMPLETED 后才可启动）。
     */
    @PostMapping("/tasks/{taskId}/start")
    public PlanTaskView startTask(@PathVariable String incidentKey, @PathVariable String taskId,
                                  @RequestHeader("X-Actor-Id") String actor,
                                  @RequestBody PlanTaskActionRequest req) {
        return service.startTask(incidentKey, taskId, actor, req);
    }

    /**
     * 完成方案任务（仅 IN_PROGRESS；COMPLETED 为终态，完成事实不可回退）。
     */
    @PostMapping("/tasks/{taskId}/complete")
    public PlanTaskView completeTask(@PathVariable String incidentKey, @PathVariable String taskId,
                                     @RequestHeader("X-Actor-Id") String actor,
                                     @RequestBody PlanTaskActionRequest req) {
        return service.completeTask(incidentKey, taskId, actor, req);
    }
}
