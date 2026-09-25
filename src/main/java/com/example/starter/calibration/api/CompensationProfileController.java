package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CompensationProfileResponse;
import com.example.starter.calibration.api.dto.UpsertProfileRequest;
import com.example.starter.calibration.service.CompensationProfileService;

/**
 * 环境补偿系数版本接口：创建/更新（追加新版本）、按 ID 查询、按型号列出全部版本。
 */
@RestController
@RequestMapping("/api/compensation-profiles")
public class CompensationProfileController {

    private final CompensationProfileService profiles;

    public CompensationProfileController(CompensationProfileService profiles) {
        this.profiles = profiles;
    }

    /**
     * 创建或更新（型号已有版本则追加并激活新版本）：201；参数非法 400；同输入并发由 calcKey 幂等重放。
     */
    @PostMapping
    public ResponseEntity<CompensationProfileResponse> upsert(@RequestBody UpsertProfileRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(profiles.upsert(request));
    }

    /**
     * 按 ID 查询系数版本：200；不存在 404。
     */
    @GetMapping("/{id}")
    public CompensationProfileResponse get(@PathVariable long id) {
        return profiles.get(id);
    }

    /**
     * 按仪器型号列出全部系数版本（版本号升序）。
     */
    @GetMapping
    public List<CompensationProfileResponse> listByModel(@RequestParam String instrumentModel) {
        return profiles.listByModel(instrumentModel);
    }
}
