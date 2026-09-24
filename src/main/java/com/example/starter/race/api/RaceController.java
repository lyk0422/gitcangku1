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

    /** 一次性划分分组（2~8 组、每组 2~16 人、同一选手只属一组）。 */
    @PostMapping("/{raceId}/groups")
    public ResponseEntity<Object> assignGroups(
            @PathVariable String raceId,
            @Valid @RequestBody AssignGroupsRequest request) {
        return toResponse(raceService.assignGroups(raceId, request));
    }

    /** 查询赛事分组划分。 */
    @GetMapping("/{raceId}/groups")
    public GroupsResponse getGroups(@PathVariable String raceId) {
        return raceService.getGroups(raceId);
    }

    /** 原子生成晋级名单并写入不可变快照。 */
    @PostMapping("/{raceId}/advancement")
    public ResponseEntity<Object> generateAdvancement(
            @PathVariable String raceId,
            @Valid @RequestBody GenerateAdvancementRequest request) {
        return toResponse(raceService.generateAdvancement(raceId, request));
    }

    /** 整份撤销当前生效晋级名单（原快照保留）。 */
    @PostMapping("/{raceId}/advancement/revocation")
    public ResponseEntity<Object> revokeAdvancement(
            @PathVariable String raceId,
            @Valid @RequestBody RevokeAdvancementRequest request) {
        return toResponse(raceService.revokeAdvancement(raceId, request));
    }

    /** 查询当前生效的晋级名单。 */
    @GetMapping("/{raceId}/advancement")
    public AdvancementResponse getActiveAdvancement(@PathVariable String raceId) {
        return raceService.getActiveAdvancement(raceId);
    }

    /** 按键查询晋级名单快照（含已撤销）。 */
    @GetMapping("/{raceId}/advancement/{advancementKey}")
    public AdvancementResponse getAdvancement(
            @PathVariable String raceId,
            @PathVariable String advancementKey) {
        return raceService.getAdvancement(raceId, advancementKey);
    }

    /** 查询未晋级清单（已划入分组但不在当前生效名单中的选手）。 */
    @GetMapping("/{raceId}/advancement-non-advanced")
    public NonAdvancedResponse getNonAdvanced(@PathVariable String raceId) {
        return raceService.getNonAdvanced(raceId);
    }

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
