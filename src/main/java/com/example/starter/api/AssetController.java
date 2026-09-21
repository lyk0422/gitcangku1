package com.example.starter.api;

import com.example.starter.api.dto.Requests;
import com.example.starter.api.dto.Responses;
import com.example.starter.service.AssetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 素材接口。
 */
@RestController
@RequestMapping("/assets")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Responses.AssetView create(@RequestBody Requests.CreateAsset request) {
        return ViewMapper.toView(assetService.create(request.id(), request.durationMs()));
    }

    @GetMapping("/{id}")
    public Responses.AssetView get(@PathVariable String id) {
        return ViewMapper.toView(assetService.get(id));
    }
}
