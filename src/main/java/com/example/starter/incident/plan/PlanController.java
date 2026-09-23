package com.example.starter.incident.plan;

import com.example.starter.incident.dto.Requests.PlanCreateRequest;
import com.example.starter.incident.dto.Requests.PlanMergeRequest;
import com.example.starter.incident.dto.Requests.PlanTaskActionRequest;
import com.example.starter.incident.dto.Requests.RevisionCreateRequest;
import com.example.starter.incident.dto.Requests.RevisionUpdateRequest;
import com.example.starter.incident.dto.Responses.DiffView;
import com.example.starter.incident.dto.Responses.MergeEvidenceView;
import com.example.starter.incident.dto.Responses.PlanTaskView;
import com.example.starter.incident.dto.Responses.PlanVersionView;
import com.example.starter.incident.dto.Responses.PlanView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * 处置方案 REST API：方案/草稿修订/三方合并/合并证据/活动版本任务执行。
 * 写操作均要求 X-Actor-Id 请求头标识操作人。
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanService planService;
    private final PlanMergeService mergeService;

    public PlanController(PlanService planService, PlanMergeService mergeService) {
        this.planService = planService;
        this.mergeService = mergeService;
    }

    /**
     * 创建方案（含空的初始 PUBLISHED 版本 1）。
     */
    @PostMapping
    public ResponseEntity<PlanView> createPlan(@RequestHeader("X-Actor-Id") String actor,
                                               @RequestBody PlanCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(planService.createPlan(actor, req));
    }

    /**
     * 查询方案当前视图（含活动版本号，只读）。
     */
    @GetMapping("/{planKey}")
    public PlanView getPlan(@PathVariable String planKey) {
        return planService.getPlan(planKey);
    }

    /**
     * 从当前活动 PUBLISHED 版本创建 DRAFT 修订。
     */
    @PostMapping("/{planKey}/revisions")
    public ResponseEntity<PlanVersionView> createRevision(@PathVariable String planKey,
                                                          @RequestHeader("X-Actor-Id") String actor,
                                                          @RequestBody RevisionCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(planService.createRevision(planKey, actor, req));
    }

    /**
     * 整体替换草稿任务集与边集（expectedVersion 乐观锁）。
     */
    @PutMapping("/{planKey}/revisions/{versionNo}")
    public PlanVersionView updateRevision(@PathVariable String planKey,
                                          @PathVariable int versionNo,
                                          @RequestHeader("X-Actor-Id") String actor,
                                          @RequestBody RevisionUpdateRequest req) {
        return planService.updateRevision(planKey, versionNo, actor, req);
    }

    /**
     * 查询版本详情（任务集与边集，稳定排序，只读）。
     */
    @GetMapping("/{planKey}/versions/{versionNo}")
    public PlanVersionView getVersion(@PathVariable String planKey, @PathVariable int versionNo) {
        return planService.getVersion(planKey, versionNo);
    }

    /**
     * 三方差异查询：自动采用项与全部显式冲突（含 conflictKey），只读、稳定排序。
     */
    @GetMapping("/{planKey}/diff")
    public DiffView diff(@PathVariable String planKey, @RequestParam int baseVersion,
                         @RequestParam int leftVersion, @RequestParam int rightVersion) {
        return planService.diff(planKey, baseVersion, leftVersion, rightVersion);
    }

    /**
     * 三方合并并原子发布：requestId 同参重放首次快照，异参 409，失败不占键。
     */
    @PostMapping("/{planKey}/merges")
    public MergeEvidenceView merge(@PathVariable String planKey,
                                   @RequestHeader("X-Actor-Id") String actor,
                                   @RequestBody PlanMergeRequest req) {
        return mergeService.merge(planKey, actor, req);
    }

    /**
     * 合并证据查询：冻结的三方差异、全部冲突解决、最终任务集与边集（只读）。
     */
    @GetMapping("/{planKey}/merges/{mergeKey}")
    public MergeEvidenceView getMerge(@PathVariable String planKey,
                                      @PathVariable String mergeKey) {
        return mergeService.getMerge(planKey, mergeKey);
    }

    /**
     * 开始执行活动版本任务（全部前置任务 COMPLETED 后才可开始）。
     */
    @PostMapping("/{planKey}/tasks/{taskId}/start")
    public PlanTaskView startTask(@PathVariable String planKey, @PathVariable String taskId,
                                  @RequestHeader("X-Actor-Id") String actor,
                                  @RequestBody PlanTaskActionRequest req) {
        return planService.startTask(planKey, taskId, actor, req);
    }

    /**
     * 完成活动版本任务（记录完成事实，不可回退）。
     */
    @PostMapping("/{planKey}/tasks/{taskId}/complete")
    public PlanTaskView completeTask(@PathVariable String planKey, @PathVariable String taskId,
                                     @RequestHeader("X-Actor-Id") String actor,
                                     @RequestBody PlanTaskActionRequest req) {
        return planService.completeTask(planKey, taskId, actor, req);
    }
}
