package com.example.gitcangku1;

import com.example.gitcangku1.domain.MeasureType;
import com.example.gitcangku1.service.BatchService;
import com.example.gitcangku1.service.ReleaseService;
import java.time.Instant;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地启动合成数据：目标(50,0,0)。
 * DEMO-001 两卷：(51,1,1)距离约1.732合格、(53,0,0)距离3.0超差，演示整批拒绝；
 * DEMO-002 单卷(51,1,1)，演示整批放行冻结。
 */
@Component
@ConditionalOnProperty(name = "app.demo-data.enabled", havingValue = "true")
public class DemoDataRunner implements ApplicationRunner {

    private final BatchService batchService;
    private final ReleaseService releaseService;

    public DemoDataRunner(BatchService batchService, ReleaseService releaseService) {
        this.batchService = batchService;
        this.releaseService = releaseService;
    }

    @Override
    public void run(ApplicationArguments args) {
        var rejected = batchService.createBatch("DEMO-001", 50, 0, 0, 2);
        batchService.recordMeasurement(rejected.id(), "demo-m1", 1, MeasureType.ORIGINAL,
                51, 1, 1, Instant.parse("2026-10-01T08:00:00Z"));
        batchService.recordMeasurement(rejected.id(), "demo-m2", 2, MeasureType.ORIGINAL,
                53, 0, 0, Instant.parse("2026-10-01T08:05:00Z"));

        var released = batchService.createBatch("DEMO-002", 50, 0, 0, 1);
        batchService.recordMeasurement(released.id(), "demo-m3", 1, MeasureType.ORIGINAL,
                51, 1, 1, Instant.parse("2026-10-01T09:00:00Z"));
        releaseService.release(released.id(), "demo-r1", null);
    }
}