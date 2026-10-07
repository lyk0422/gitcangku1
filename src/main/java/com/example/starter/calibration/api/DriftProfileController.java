package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CreateDriftProfileRequest;
import com.example.starter.calibration.api.dto.DriftProfileResponse;
import com.example.starter.calibration.api.dto.RevokeDriftProfileRequest;
import com.example.starter.calibration.service.DriftProfileService;

/**
 * 参考漂移档案接口：创建、撤销、查询。写操作使用全局 requestId 幂等。
 */
@RestController
@RequestMapping("/api/drift-profiles")
public class DriftProfileController {

    private final DriftProfileService profiles;

    public DriftProfileController(DriftProfileService profiles) {
        this.profiles = profiles;
    }

    /**
     * 创建漂移档案：201；参数非法或超精度 400；requestId 异参冲突 409。
     */
    @PostMapping
    public ResponseEntity<DriftProfileResponse> create(@RequestBody CreateDriftProfileRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(profiles.create(request));
    }

    /**
     * 撤销漂移档案：200；不存在 404；重复撤销 409。
     */
    @PostMapping("/{id}/revoke")
    public DriftProfileResponse revoke(@PathVariable long id,
                                       @RequestBody RevokeDriftProfileRequest request) {
        return profiles.revoke(id, request);
    }

    /**
     * 查询漂移档案：200；不存在 404。
     */
    @GetMapping("/{id}")
    public DriftProfileResponse get(@PathVariable long id) {
        return profiles.get(id);
    }
}
