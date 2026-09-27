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

    /** 登记选手医疗暂停。 */
    @PostMapping("/{raceId}/runners/{bib}/medical-holds")
    public ResponseEntity<Object> startMedicalHold(
            @PathVariable String raceId,
            @PathVariable String bib,
            @Valid @RequestBody StartMedicalHoldRequest request) {
        return toResponse(raceService.startMedicalHold(raceId, bib, request));
    }

    /** 恢复选手医疗暂停（由不同医疗角色确认适赛）。 */
    @PostMapping("/{raceId}/runners/{bib}/medical-holds/{holdId}/resume")
    public ResponseEntity<Object> resumeMedicalHold(
            @PathVariable String raceId,
            @PathVariable String bib,
            @PathVariable String holdId,
            @Valid @RequestBody ResumeMedicalHoldRequest request) {
        return toResponse(raceService.resumeMedicalHold(raceId, bib, holdId, request));
    }

    /** 查询单个医疗暂停明细。 */
    @GetMapping("/{raceId}/runners/{bib}/medical-holds/{holdId}")
    public MedicalHoldResponse getMedicalHold(
            @PathVariable String raceId,
            @PathVariable String bib,
            @PathVariable String holdId) {
        return raceService.getMedicalHold(raceId, bib, holdId);
    }

    /** 查询选手医疗暂停历史。 */
    @GetMapping("/{raceId}/runners/{bib}/medical-holds")
    public MedicalHoldHistoryResponse getMedicalHoldHistory(
            @PathVariable String raceId,
            @PathVariable String bib) {
        return raceService.getMedicalHoldHistory(raceId, bib);
    }

    /** 查询选手被排除出排名的分段计时（诊断）。 */
    @GetMapping("/{raceId}/runners/{bib}/excluded-timings")
    public ExcludedTimingsResponse getExcludedTimings(
            @PathVariable String raceId,
            @PathVariable String bib) {
        return raceService.getExcludedTimings(raceId, bib);
    }

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
