package com.example.starter.config;

import com.example.starter.domain.RunMode;
import com.example.starter.repo.RunSegmentRepository;
import com.example.starter.service.WellService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 合成井况数据初始化：仅在 app.seed-enabled=true（本地运行）时写入，测试库不写入。
 */
@Component
@ConditionalOnProperty(name = "app.seed-enabled", havingValue = "true")
public class DataInitializer implements ApplicationRunner {

    /** 合成井号。 */
    public static final String SEED_WELL = "GW-001";

    private final WellService wellService;
    private final RunSegmentRepository segmentRepo;

    public DataInitializer(WellService wellService, RunSegmentRepository segmentRepo) {
        this.wellService = wellService;
        this.segmentRepo = segmentRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (segmentRepo.countByWell(SEED_WELL) == 0) {
            wellService.createSegment(
                    SEED_WELL, "seed-init", RunMode.INJECTION, Instant.now().minus(1, ChronoUnit.HOURS));
        }
    }
}