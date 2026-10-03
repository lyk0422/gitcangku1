package com.example.starter.cue.web;

import com.example.starter.cue.domain.CueItem;
import com.example.starter.cue.domain.CueVersion;
import com.example.starter.cue.service.CueVersionService;
import com.example.starter.cue.web.dto.CreateVersionRequest;
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

import java.util.List;
import java.util.Map;

/**
 * 提示点编排接口：建版本、冻结、查看与版本差异。
 */
@RestController
@RequestMapping("/api/versions")
public class CueVersionController {

    private final CueVersionService service;

    public CueVersionController(CueVersionService service) {
        this.service = service;
    }

    /** 新建草稿版本，返回版本 ID。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateVersionRequest request) {
        List<CueItem> cues = request.cues().stream()
                .map(c -> new CueItem(0, 0, c.seq(), c.cueType(), c.triggerPhrase(),
                        c.durationSeconds()))
                .toList();
        long versionId = service.createVersion(request.production(), cues);
        return Map.of("versionId", versionId);
    }

    /** 冻结版本；任一提示点无效则整体拒绝并返回全部原因。 */
    @PostMapping("/{versionId}/freeze")
    public CueVersion freeze(@PathVariable long versionId) {
        return service.freezeVersion(versionId);
    }

    /** 查看版本及全部提示点。 */
    @GetMapping("/{versionId}")
    public Map<String, Object> describe(@PathVariable long versionId) {
        return service.describe(versionId);
    }

    /** 比较两个版本的提示点差异。 */
    @GetMapping("/diff")
    public Map<String, Object> diff(@RequestParam long from, @RequestParam long to) {
        return service.diff(from, to);
    }
}