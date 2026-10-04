package com.example.starter.cue;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提示点编排版本接口。 */
@RestController
@RequestMapping("/api/cue-versions")
public class CueController {

    private final CueService cueService;

    public CueController(CueService cueService) {
        this.cueService = cueService;
    }

    /** 创建新版本，每次编排修改产生一个版本，返回版本ID。 */
    @PostMapping
    public ResponseEntity<Map<String, Long>> create(@RequestBody CueDtos.CreateVersionRequest request) {
        long versionId = cueService.createVersion(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("versionId", versionId));
    }

    /** 冻结版本：全部提示点校验通过才冻结，否则整体拒绝。 */
    @PostMapping("/{id}/freeze")
    public CueVersion freeze(@PathVariable long id) {
        return cueService.freeze(id);
    }

    /** 查询两个版本的提示点差异。 */
    @GetMapping("/diff")
    public CueDtos.VersionDiff diff(@RequestParam long from, @RequestParam long to) {
        return cueService.diff(from, to);
    }
}