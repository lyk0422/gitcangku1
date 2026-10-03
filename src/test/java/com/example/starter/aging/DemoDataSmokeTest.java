package com.example.starter.aging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.aging.service.AgingService;
import com.example.starter.aging.web.ApiViews.BoardView;
import com.example.starter.aging.web.ApiViews.ReleaseView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 合成演示数据冒烟测试：使用主配置的 schema.sql 与 data.sql（独立命名内存库），
 * 验证本地启动同款初始化可加载，并走通一次合成放行调用。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cheese_demo_smoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.sql.init.data-locations=classpath:data.sql"
})
class DemoDataSmokeTest {

    @Autowired
    private AgingService service;

    @Test
    void demoDataLoadsAndReleaseCallSucceeds() {
        // 合成批次 CHEESE-DEMO-001：七天翻面与有效温度齐全
        BoardView board = service.board(1L);
        assertThat(board.batchCode()).isEqualTo("CHEESE-DEMO-001");
        assertThat(board.status()).isEqualTo("AGING");
        assertThat(board.days()).hasSize(7);
        assertThat(board.days()).allSatisfy(day -> {
            assertThat(day.turn()).isNotNull();
            assertThat(day.validTemperatures()).isNotEmpty();
        });

        // 合成业务调用：放行该批次，预期成功并固化七天快照；同 requestId 重放幂等
        ReleaseView release = service.release(1L, "smoke-release-1");
        assertThat(release.snapshot()).hasSize(7);
        assertThat(service.release(1L, "smoke-release-1").releaseId())
                .isEqualTo(release.releaseId());
        assertThat(service.board(1L).release().releaseId()).isEqualTo(release.releaseId());
    }
}