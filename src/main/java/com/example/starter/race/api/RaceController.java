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

    /** 提交器材检录/复检。 */
    @PostMapping("/{raceId}/runners/{bib}/inspections")
    public ResponseEntity<Object> submitInspection(
            @PathVariable String raceId,
            @PathVariable String bib,
            @Valid @RequestBody SubmitInspectionRequest request) {
        return toResponse(raceService.submitInspection(raceId, bib, request));
    }

    /** 选手起跑。 */
    @PostMapping("/{raceId}/runners/{bib}/start")
    public ResponseEntity<Object> startRunner(
            @PathVariable String raceId,
            @PathVariable String bib,
            @Valid @RequestBody StartRunnerRequest request) {
        return toResponse(raceService.startRunner(raceId, bib, request));
    }

    /** 选手退赛。 */
    @PostMapping("/{raceId}/runners/{bib}/withdrawal")
    public ResponseEntity<Object> withdrawRunner(
            @PathVariable String raceId,
            @PathVariable String bib,
            @Valid @RequestBody WithdrawRunnerRequest request) {
        return toResponse(raceService.withdrawRunner(raceId, bib, request));
    }

    /** 查询选手检录历史（不可变，按时间升序）。 */
    @GetMapping("/{raceId}/runners/{bib}/inspections")
    public InspectionHistoryResponse getInspectionHistory(
            @PathVariable String raceId,
            @PathVariable String bib) {
        return raceService.getInspectionHistory(raceId, bib);
    }

    /** 查询选手当前有效检录状态。 */
    @GetMapping("/{raceId}/runners/{bib}/inspection-status")
    public InspectionStatusResponse getInspectionStatus(
            @PathVariable String raceId,
            @PathVariable String bib) {
        return raceService.getInspectionStatus(raceId, bib);
    }

    /** 查询赛事器材当前绑定清单。 */
    @GetMapping("/{raceId}/equipment-bindings")
    public EquipmentBindingsResponse getEquipmentBindings(@PathVariable String raceId) {
        return raceService.getEquipmentBindings(raceId);
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

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
