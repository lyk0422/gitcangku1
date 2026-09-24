package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 观测按时刻一致视图与冻结快照 API：按时刻查询（只读）、创建冻结快照、按 snapshotKey 读取不可变快照。
 */
@RestController
@RequestMapping("/api/observations")
@Validated
public class SnapshotController {

    private final SnapshotService snapshotService;

    public SnapshotController(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    /**
     * 按时刻查询一致视图：返回每条记录在该时刻的最后版本与最近冲突解决标识；只读。
     */
    @PostMapping("/as-of")
    public AsOfResponse asOf(@Valid @RequestBody AsOfQueryRequest request) {
        return snapshotService.queryAsOf(request);
    }

    /**
     * 创建冻结快照：单事务读取一致状态并保存不可变快照。
     */
    @PostMapping("/snapshots")
    public ResponseEntity<SnapshotResponse> createSnapshot(@Valid @RequestBody CreateSnapshotRequest request) {
        SnapshotService.SnapshotOutcome outcome = snapshotService.createSnapshot(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按全局唯一 snapshotKey 读取不可变冻结快照；不存在返回 404。
     */
    @GetMapping("/snapshots/{snapshotKey}")
    public SnapshotResponse getSnapshot(@PathVariable String snapshotKey) {
        return snapshotService.getSnapshot(snapshotKey);
    }
}
