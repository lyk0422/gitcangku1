package com.example.starter.race.api;

import com.example.starter.race.service.RaceService;
import com.example.starter.race.service.ServiceResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/**
 * 赛事计时处罚与成绩封榜 HTTP 接口。
 */
@RestController
@RequestMapping("/api/races")
public class RaceController {

    private final RaceService raceService;

    public RaceController(RaceService raceService) {
        this.raceService = raceService;
    }

    /** 新建赛事。 */
    @PostMapping
    public ResponseEntity<Object> createRace(@Valid @RequestBody CreateRaceRequest request) {
        return toResponse(raceService.createRace(request));
    }

    /** 登记选手。 */
    @PostMapping("/{raceId}/runners")
    public ResponseEntity<Object> registerRunner(
            @PathVariable String raceId,
            @Valid @RequestBody RegisterRunnerRequest request) {
        return toResponse(raceService.registerRunner(raceId, request));
    }

    /** 计时修订。 */
    @PostMapping("/{raceId}/timing-revisions")
    public ResponseEntity<Object> reviseTime(
            @PathVariable String raceId,
            @Valid @RequestBody ReviseTimeRequest request) {
        return toResponse(raceService.reviseTime(raceId, request));
    }

    /** 新增处罚。 */
    @PostMapping("/{raceId}/penalties")
    public ResponseEntity<Object> addPenalty(
            @PathVariable String raceId,
            @Valid @RequestBody AddPenaltyRequest request) {
        return toResponse(raceService.addPenalty(raceId, request));
    }

    /** 撤销处罚。 */
    @PostMapping("/{raceId}/penalties/{penaltyId}/revocation")
    public ResponseEntity<Object> revokePenalty(
            @PathVariable String raceId,
            @PathVariable String penaltyId,
            @Valid @RequestBody RevokePenaltyRequest request) {
        return toResponse(raceService.revokePenalty(raceId, penaltyId, request));
    }

    /** 一次性配置赛事检查点。 */
    @PostMapping("/{raceId}/checkpoints")
    public ResponseEntity<Object> configureCheckpoints(
            @PathVariable String raceId,
            @Valid @RequestBody ConfigureCheckpointsRequest request) {
        return toResponse(raceService.configureCheckpoints(raceId, request));
    }

    /** 为选手提交检查点通过记录。 */
    @PostMapping("/{raceId}/runners/{bib}/timings")
    public ResponseEntity<Object> submitTiming(
            @PathVariable String raceId,
            @PathVariable String bib,
            @Valid @RequestBody SubmitTimingRequest request) {
        return toResponse(raceService.submitTiming(raceId, bib, request));
    }

    /** 封榜。 */
    @PostMapping("/{raceId}/seal")
    public ResponseEntity<Object> sealRace(
            @PathVariable String raceId,
            @Valid @RequestBody SealRaceRequest request) {
        return toResponse(raceService.sealRace(raceId, request));
    }

    /** 查询即时成绩（封榜后返回只读快照内容）。 */
    @GetMapping("/{raceId}/results")
    public StandingResponse getResults(@PathVariable String raceId) {
        return raceService.getResults(raceId);
    }

    /** 查询封榜快照。 */
    @GetMapping("/{raceId}/snapshot")
    public StandingResponse getSnapshot(@PathVariable String raceId) {
        return raceService.getSnapshot(raceId);
    }

    /** 查询单个选手的分段明细（按检查点顺序）。 */
    @GetMapping("/{raceId}/runners/{bib}/timings")
    public RunnerTimingResponse getRunnerTimings(
            @PathVariable String raceId,
            @PathVariable String bib) {
        return raceService.getRunnerTimings(raceId, bib);
    }

    /** 查询赛事缺失检查点汇总。 */
    @GetMapping("/{raceId}/missing-checkpoints")
    public MissingCheckpointsResponse getMissingCheckpoints(@PathVariable String raceId) {
        return raceService.getMissingCheckpoints(raceId);
    }

    /** 新建组别。 */
    @PostMapping("/{raceId}/categories")
    public ResponseEntity<Object> createCategory(
            @PathVariable String raceId,
            @Valid @RequestBody CreateCategoryRequest request) {
        return toResponse(raceService.createCategory(raceId, request));
    }

    /** 查询赛事全部组别。 */
    @GetMapping("/{raceId}/categories")
    public java.util.List<CategoryResponse> listCategories(@PathVariable String raceId) {
        return raceService.listCategories(raceId);
    }

    /** 组别封榜：冻结该组别当前排名为只读快照。 */
    @PostMapping("/{raceId}/categories/{categoryCode}/seal")
    public ResponseEntity<Object> sealCategory(
            @PathVariable String raceId,
            @PathVariable String categoryCode,
            @Valid @RequestBody SealRaceRequest request) {
        return toResponse(raceService.sealCategory(raceId, categoryCode, request));
    }

    /** 查询组别成绩（封榜后返回组别快照内容）。 */
    @GetMapping("/{raceId}/categories/{categoryCode}/results")
    public CategoryStandingResponse getCategoryResults(
            @PathVariable String raceId,
            @PathVariable String categoryCode) {
        return raceService.getCategoryResults(raceId, categoryCode);
    }

    /** 查询组别封榜快照。 */
    @GetMapping("/{raceId}/categories/{categoryCode}/snapshot")
    public CategoryStandingResponse getCategorySnapshot(
            @PathVariable String raceId,
            @PathVariable String categoryCode) {
        return raceService.getCategorySnapshot(raceId, categoryCode);
    }

    /** 选手退赛。 */
    @PostMapping("/{raceId}/runners/{bib}/withdrawal")
    public ResponseEntity<Object> withdrawRunner(
            @PathVariable String raceId,
            @PathVariable String bib,
            @Valid @RequestBody WithdrawRunnerRequest request) {
        return toResponse(raceService.withdrawRunner(raceId, bib, request));
    }

    /** 提交组别重分申请。 */
    @PostMapping("/{raceId}/reclass-requests")
    public ResponseEntity<Object> submitReclassApplication(
            @PathVariable String raceId,
            @Valid @RequestBody SubmitReclassApplicationRequest request) {
        return toResponse(raceService.submitReclassApplication(raceId, request));
    }

    /** 查询赛事全部重分申请。 */
    @GetMapping("/{raceId}/reclass-requests")
    public java.util.List<ReclassApplicationResponse> listReclassApplications(
            @PathVariable String raceId) {
        return raceService.listReclassApplications(raceId);
    }

    /** 拒绝重分申请（不改变成绩）。 */
    @PostMapping("/{raceId}/reclass-requests/{applicationId}/rejection")
    public ResponseEntity<Object> rejectReclassApplication(
            @PathVariable String raceId,
            @PathVariable String applicationId,
            @Valid @RequestBody RejectReclassRequest request) {
        return toResponse(raceService.rejectReclassApplication(raceId, applicationId, request));
    }

    /** 批量批准重分申请：同一事务迁移并返回重算后的来源与目标组别排名。 */
    @PostMapping("/{raceId}/reclass-approvals")
    public ResponseEntity<Object> approveReclass(
            @PathVariable String raceId,
            @Valid @RequestBody ApproveReclassRequest request) {
        return toResponse(raceService.approveReclass(raceId, request));
    }

    /** 查询赛事全部不可变重分记录。 */
    @GetMapping("/{raceId}/reclass-records")
    public java.util.List<ReclassRecordResponse> listReclassRecords(
            @PathVariable String raceId) {
        return raceService.listReclassRecords(raceId);
    }

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
