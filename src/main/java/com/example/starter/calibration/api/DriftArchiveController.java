package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CreateDriftArchiveRequest;
import com.example.starter.calibration.api.dto.DriftArchiveResponse;
import com.example.starter.calibration.api.dto.RevokeRequest;
import com.example.starter.calibration.service.DriftArchiveService;

/**
 * 参考漂移档案接口：创建（不可修改）、撤销、查询。
 */
@RestController
@RequestMapping("/api/drift-archives")
public class DriftArchiveController {

    private final DriftArchiveService archives;

    public DriftArchiveController(DriftArchiveService archives) {
        this.archives = archives;
    }

    /**
     * 创建漂移档案：201；参数或精度非法 400。
     */
    @PostMapping
    public ResponseEntity<DriftArchiveResponse> create(@RequestBody CreateDriftArchiveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(archives.create(request));
    }

    /**
     * 撤销漂移档案：200；不存在 404；重复撤销 409。请求体可选，用于幂等去重。
     */
    @PostMapping("/{id}/revoke")
    public DriftArchiveResponse revoke(@PathVariable long id,
                                       @RequestBody(required = false) RevokeRequest request) {
        return archives.revoke(id, request);
    }

    /**
     * 查询漂移档案：200；不存在 404。
     */
    @GetMapping("/{id}")
    public DriftArchiveResponse get(@PathVariable long id) {
        return archives.get(id);
    }
}
