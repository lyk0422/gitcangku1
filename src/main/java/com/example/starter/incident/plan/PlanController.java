package com.example.starter.incident.plan;

import com.example.starter.incident.plan.dto.PlanRequests.BranchCreateRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftEdgeRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftTaskRequest;
import com.example.starter.incident.plan.dto.PlanRequests.MergeRequest;
import com.example.starter.incident.plan.dto.PlanRequests.PlanVersionCreateRequest;
import com.example.starter.incident.plan.dto.PlanRequests.TaskCompleteRequest;
import com.example.starter.incident.plan.dto.PlanRequests.TaskStartRequest;
import com.example.starter.incident.plan.dto.PlanResponses.MergeEvidenceView;
import com.example.starter.incident.plan.dto.PlanResponses.MergeView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanDiffView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanTaskView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanVersionView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 处置方案分支与三方合并 REST API。写操作均要求 X-Actor-Id 请求头标识操作人
 * （须为当前指挥人）；差异与合并证据查询只读。
 */
@RestController
@RequestMapping("/api/incidents/{incidentKey}")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    /**
     * 创建并发布初始方案版本（任务集 + 依赖边，拒绝成环）。
     */
    @PostMapping("/plan-versions")
    public ResponseEntity<PlanVersionView> createInitialVersion(@PathVariable String incidentKey,
            @RequestHeader("X-Actor-Id") String actor,
            @RequestBody PlanVersionCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createInitialVersion(incidentKey, actor, req));
    }

    /**
     * 查询当前活动方案版本（含任务执行态与依赖边，只读）。
     */
    @GetMapping("/plan")
    public PlanVersionView getActivePlan(@PathVariable String incidentKey) {
        return service.getActivePlan(incidentKey);
    }

    /**
     * 查询指定方案版本（只读）。
     */
    @GetMapping("/plan-versions/{versionId}")
    public PlanVersionView getVersion(@PathVariable String incidentKey,
                                      @PathVariable long versionId) {
        return service.getVersion(incidentKey, versionId);
    }

    /**
     * 从当前活动 PUBLISHED 版本创建 DRAFT 分支（同一基准至多 LEFT/RIGHT 两个）。
     */
    @PostMapping("/plan-versions/{versionId}/branches")
    public ResponseEntity<PlanVersionView> createBranch(@PathVariable String incidentKey,
            @PathVariable long versionId,
            @RequestHeader("X-Actor-Id") String actor,
            @RequestBody BranchCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createBranch(incidentKey, actor, versionId, req));
    }

    /**
     * 写入草稿任务（存在即改、不存在即增），草稿修订计数 +1。
     */
    @PutMapping("/plan-versions/{versionId}/tasks/{taskId}")
    public PlanVersionView upsertDraftTask(@PathVariable String incidentKey,
                                           @PathVariable long versionId,
                                           @PathVariable String taskId,
                                           @RequestHeader("X-Actor-Id") String actor,
                                           @RequestBody DraftTaskRequest req) {
        return service.upsertDraftTask(incidentKey, actor, versionId, taskId, req);
    }

    /**
     * 删除草稿任务（级联删除相关边），草稿修订计数 +1。
     */
    @DeleteMapping("/plan-versions/{versionId}/tasks/{taskId}")
    public PlanVersionView deleteDraftTask(@PathVariable String incidentKey,
                                           @PathVariable long versionId,
                                           @PathVariable String taskId,
                                           @RequestHeader("X-Actor-Id") String actor) {
        return service.deleteDraftTask(incidentKey, actor, versionId, taskId);
    }

    /**
     * 新增草稿依赖边（拒绝自环、重复边与草稿内成环），草稿修订计数 +1。
     */
    @PostMapping("/plan-versions/{versionId}/edges")
    public PlanVersionView addDraftEdge(@PathVariable String incidentKey,
                                        @PathVariable long versionId,
                                        @RequestHeader("X-Actor-Id") String actor,
                                        @RequestBody DraftEdgeRequest req) {
        return service.addDraftEdge(incidentKey, actor, versionId, req);
    }

    /**
     * 删除草稿依赖边，草稿修订计数 +1。
     */
    @DeleteMapping("/plan-versions/{versionId}/edges")
    public PlanVersionView deleteDraftEdge(@PathVariable String incidentKey,
                                           @PathVariable long versionId,
                                           @RequestHeader("X-Actor-Id") String actor,
                                           @RequestBody DraftEdgeRequest req) {
        return service.deleteDraftEdge(incidentKey, actor, versionId, req);
    }

    /**
     * 三方差异查询（只读）：自动采用项与全部显式冲突，稳定排序。
     */
    @GetMapping("/plan-merge/diff")
    public PlanDiffView diff(@PathVariable String incidentKey,
                             @RequestParam long baseVersionId,
                             @RequestParam long leftVersionId,
                             @RequestParam long rightVersionId) {
        return service.diff(incidentKey, baseVersionId, leftVersionId, rightVersionId);
    }

    /**
     * 提交合并（原子发布）：一次提交全部冲突解决；任一分支变化、活动版本
     * 已前进或完整后态违规，整次 409/422 且不生成合并版本。
     */
    @PostMapping("/plan-merges")
    public MergeView merge(@PathVariable String incidentKey,
                           @RequestHeader("X-Actor-Id") String actor,
                           @RequestBody MergeRequest req) {
        return service.merge(incidentKey, actor, req);
    }

    /**
     * 查询合并证据：冻结的三方差异、全部冲突解决、最终任务集与边集（只读）。
     */
    @GetMapping("/plan-merges/{mergeKey}")
    public MergeEvidenceView getMergeEvidence(@PathVariable String incidentKey,
                                              @PathVariable String mergeKey) {
        return service.getMergeEvidence(incidentKey, mergeKey);
    }

    /**
     * 开始执行活动版本任务（须已指派负责人且前置全部完成）。
     */
    @PostMapping("/plan/tasks/{taskId}/start")
    public PlanTaskView startTask(@PathVariable String incidentKey, @PathVariable String taskId,
                                  @RequestHeader("X-Actor-Id") String actor,
                                  @RequestBody TaskStartRequest req) {
        return service.startTask(incidentKey, actor, taskId, req);
    }

    /**
     * 完成执行中的任务（记录完成人与 UTC 时刻，完成事实不可回退）。
     */
    @PostMapping("/plan/tasks/{taskId}/complete")
    public PlanTaskView completeTask(@PathVariable String incidentKey, @PathVariable String taskId,
                                     @RequestHeader("X-Actor-Id") String actor,
                                     @RequestBody TaskCompleteRequest req) {
        return service.completePlanTask(incidentKey, actor, taskId, req);
    }
}
