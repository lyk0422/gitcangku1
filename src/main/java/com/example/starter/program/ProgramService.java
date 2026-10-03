package com.example.starter.program;

import com.example.starter.common.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 程序版本服务：创建时校验程序段按 UTC 连续覆盖烧制区间。
 */
@Service
public class ProgramService {

    private final ProgramRepository repository;

    public ProgramService(ProgramRepository repository) {
        this.repository = repository;
    }

    /**
     * 创建程序版本。段必须非空、起点早于终点、偏差非负，且相邻段首尾相接。
     */
    @Transactional
    public ProgramVersionInfo create(String name, int versionNo, List<SegmentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw ApiException.badRequest("程序段不能为空");
        }
        List<SegmentInfo> segments = new ArrayList<>();
        Instant prevEnd = null;
        for (int i = 0; i < inputs.size(); i++) {
            SegmentInput in = inputs.get(i);
            if (in.startTime() == null || in.endTime() == null || !in.startTime().isBefore(in.endTime())) {
                throw ApiException.badRequest("第" + (i + 1) + "段起点必须早于终点");
            }
            if (in.targetTempC() == null || in.toleranceC() == null
                    || in.toleranceC().compareTo(BigDecimal.ZERO) < 0) {
                throw ApiException.badRequest("第" + (i + 1) + "段目标温度必填且允许偏差非负");
            }
            if (prevEnd != null && !prevEnd.equals(in.startTime())) {
                throw ApiException.badRequest("第" + (i + 1) + "段与上一段不连续，存在时间断层或重叠");
            }
            prevEnd = in.endTime();
            segments.add(new SegmentInfo(0, i + 1, in.startTime(), in.endTime(),
                    in.targetTempC(), in.toleranceC()));
        }
        long versionId;
        try {
            versionId = repository.insertVersion(name, versionNo);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同名同版本的程序已存在: " + name + " v" + versionNo);
        }
        for (SegmentInfo seg : segments) {
            repository.insertSegment(versionId, seg.seq(), seg);
        }
        return repository.findById(versionId).orElseThrow();
    }

    /**
     * 按ID查询程序版本，不存在时报404。
     */
    public ProgramVersionInfo getRequired(long id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("程序版本不存在: " + id));
    }

    /**
     * 创建程序版本的段入参。
     */
    public record SegmentInput(Instant startTime, Instant endTime,
                               BigDecimal targetTempC, BigDecimal toleranceC) {
    }
}