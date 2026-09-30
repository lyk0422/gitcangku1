package com.example.starter;

import com.example.starter.testsupport.TestClockConfig;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试组合注解：注入可控时钟，并在每个测试方法执行前清空业务表，
 * 保证同内存库（DB_CLOSE_DELAY=-1）内场景数据互不串扰。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(TestClockConfig.class)
@TestExecutionListeners(
        value = ResetDatabaseTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface ResetDatabase {
}
