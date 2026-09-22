package com.example.starter.race;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.race.dto.AddPenaltyRequest;
import com.example.starter.race.dto.CreateRaceRequest;
import com.example.starter.race.dto.RegisterParticipantRequest;
import com.example.starter.race.dto.ReviseTimeRequest;
import com.example.starter.race.dto.RevokePenaltyRequest;
import com.example.starter.race.dto.SealRaceRequest;
import com.example.starter.race.dto.SnapshotResponse;
import com.example.starter.race.dto.StandingsResponse;

/**
 * 赛事成绩封榜 API。写接口返回的 JSON 由服务层生成（含幂等重放），读接口直接返回 DTO。
 */
@RestController
@RequestMapping("/api/races")
public class RaceController {

    private final RaceService service;

    public RaceController(RaceService service) {
        this.service = service;
    }

    /**
     * 创建赛事。
     */
    @PostMapping
    public ResponseEntity<String> createRace(@Valid @RequestBody CreateRaceRequest req) {
        return ok(service.createRace(req));
    }

    /**
     * 登记选手。
     */
    @PostMapping("/{raceId}/participants")
    public ResponseEntity<String> registerParticipant(@PathVariable String raceId,
                                                      @Valid @RequestBody RegisterParticipantRequest req) {
        return ok(service.registerParticipant(raceId, req));
    }

    /**
     * 修订选手原始完赛耗时。
     */
    @PutMapping("/{raceId}/participants/{bib}/time")
    public ResponseEntity<String> reviseTime(@PathVariable String raceId, @PathVariable String bib,
                                             @Valid @RequestBody ReviseTimeRequest req) {
        return ok(service.reviseTime(raceId, bib, req));
    }

    /**
     * 新增处罚。
     */
    @PostMapping("/{raceId}/penalties")
    public ResponseEntity<String> addPenalty(@PathVariable String raceId,
                                             @Valid @RequestBody AddPenaltyRequest req) {
        return ok(service.addPenalty(raceId, req));
    }

    /**
     * 撤销处罚。
     */
    @PostMapping("/{raceId}/penalties/{penaltyId}/revoke")
    public ResponseEntity<String> revokePenalty(@PathVariable String raceId,
                                                @PathVariable String penaltyId,
                                                @Valid @RequestBody RevokePenaltyRequest req) {
        return ok(service.revokePenalty(raceId, penaltyId, req));
    }

    /**
     * 封榜：原子保存成绩快照并转为 SEALED。
     */
    @PostMapping("/{raceId}/seal")
    public ResponseEntity<String> sealRace(@PathVariable String raceId,
                                           @Valid @RequestBody SealRaceRequest req) {
        return ok(service.sealRace(raceId, req));
    }

    /**
     * 查询即时成绩。
     */
    @GetMapping("/{raceId}/standings")
    public StandingsResponse standings(@PathVariable String raceId) {
        return service.standings(raceId);
    }

    /**
     * 查询封榜快照。
     */
    @GetMapping("/{raceId}/snapshot")
    public SnapshotResponse snapshot(@PathVariable String raceId) {
        return service.snapshot(raceId);
    }

    private ResponseEntity<String> ok(String json) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
    }
}
