package com.example.starter.cue;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.cue.CueDtos.CreateVersionRequest;
import com.example.starter.cue.CueDtos.DiffView;
import com.example.starter.cue.CueDtos.VersionView;

/**
 * 提示点编排接口：创建草稿版本、冻结、查询与版本差异。
 */
@RestController
@RequestMapping("/api")
public class CueVersionController {

    private final CueVersionService service;

    public CueVersionController(CueVersionService service) {
        this.service = service;
    }

    @PostMapping("/plays/{playCode}/cue-versions")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionView create(@PathVariable String playCode, @Valid @RequestBody CreateVersionRequest request) {
        return service.createVersion(playCode, request.cues());
    }

    @PostMapping("/cue-versions/{id}/freeze")
    public VersionView freeze(@PathVariable long id) {
        return service.freeze(id);
    }

    @GetMapping("/cue-versions/{id}")
    public VersionView get(@PathVariable long id) {
        return service.getVersion(id);
    }

    @GetMapping("/cue-versions/diff")
    public DiffView diff(@RequestParam long from, @RequestParam long to) {
        return service.diff(from, to);
    }
}