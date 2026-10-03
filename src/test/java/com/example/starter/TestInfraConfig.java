package com.example.starter;

import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 测试基础设施：可控时钟覆盖生产时钟；上下文关闭时释放 H2 内存库
 * （DB_CLOSE_DELAY=-1 只保证运行期间存活，进程退出后不保留）。
 */
@Configuration
public class TestInfraConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-10-04T08:00:00Z"));
    }

    @Bean
    public DisposableBean h2Shutdown(DataSource dataSource) {
        return () -> {
            try (var con = dataSource.getConnection(); var st = con.createStatement()) {
                st.execute("SHUTDOWN");
            } catch (Exception ignored) {
                // 数据库可能已被其他上下文关闭
            }
        };
    }
}