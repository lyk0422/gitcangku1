package com.example.starter.snow;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启动时初始化合成演示数据：封闭中的 DEMO-1 区域与最近三个整点的合规读数，
 * 便于巡查员直接查询“为何仍封闭”和演练解除审核；数据已存在时不重复写入。
 */
@Component
public class SyntheticDataSeeder implements CommandLineRunner {

    /** 演示区域标识。 */
    static final String DEMO_AREA = "DEMO-1";

    private final SnowRepository repo;
    private final Clock clock;

    public SyntheticDataSeeder(SnowRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Instant now = Instant.now(clock);
        repo.ensureArea(DEMO_AREA, "演示控制区-1", now);
        repo.lockArea(DEMO_AREA);
        if (repo.countReadings(DEMO_AREA) > 0) {
            return;
        }
        Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
        int[] newSnow = {3, 4, 5};
        for (int i = 0; i < WINDOW(); i++) {
            Instant hour = currentHour.minus(WINDOW() - i, ChronoUnit.HOURS);
            repo.insertReading(DEMO_AREA, hour, 118 + i * 2, newSnow[i], "SYNTHETIC",
                    "seed-" + DEMO_AREA + "-" + i, now);
        }
        repo.insertClosure(DEMO_AREA, "合成初始封闭：历史积雪异常", now);
    }

    private static int WINDOW() {
        return SnowService.WINDOW_HOURS;
    }
}