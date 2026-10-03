package com.example.starter.program;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 程序版本接口：创建与查询升温程序。
 */
@RestController
@RequestMapping("/api/program-versions")
public class ProgramController {

    private final ProgramService service;

    public ProgramController(ProgramService service) {
        this.service = service;
    }

    /**
     * 创建程序版本，段按 UTC 连续覆盖烧制区间。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProgramVersionInfo create(@Valid @RequestBody CreateRequest request) {
        List<ProgramService.SegmentInput> segments = request.segments().stream()
                .map(s -> new ProgramService.SegmentInput(s.startTime(), s.endTime(),
                        s.targetTempC(), s.toleranceC()))
                .toList();
        return service.create(request.name(), request.versionNo(), segments);
    }

    /**
     * 查询程序版本详情。
     */
    @GetMapping("/{id}")
    public ProgramVersionInfo get(@PathVariable long id) {
        return service.getRequired(id);
    }

    public record CreateRequest(@NotBlank String name, @NotNull Integer versionNo,
                                @NotEmpty List<@Valid SegmentDto> segments) {
    }

    public record SegmentDto(@NotNull Instant startTime, @NotNull Instant endTime,
                             @NotNull BigDecimal targetTempC, @NotNull BigDecimal toleranceC) {
    }
}