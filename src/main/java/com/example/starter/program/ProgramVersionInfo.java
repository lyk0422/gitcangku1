package com.example.starter.program;

import java.time.Instant;
import java.util.List;

/**
 * 程序版本视图：入炉后被批次锁定引用。
 *
 * @param id        版本ID
 * @param name      程序名称
 * @param versionNo 版本号
 * @param createdAt 创建时间，UTC
 * @param segments  程序段，按 seq 升序
 */
public record ProgramVersionInfo(long id, String name, int versionNo, Instant createdAt,
                                 List<SegmentInfo> segments) {

    /**
     * 烧制区间起点（首段起点），UTC。
     */
    public Instant firingStart() {
        return segments.get(0).startTime();
    }

    /**
     * 烧制区间终点（末段终点），UTC。
     */
    public Instant firingEnd() {
        return segments.get(segments.size() - 1).endTime();
    }
}