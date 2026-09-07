package com.community.residence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 社区居住服务管理系统后端启动类。
 * 单体应用：业务模块对齐 C1~C12，一域一包（架构设计.md §2）。
 */
@SpringBootApplication
public class CommunityResidenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommunityResidenceApplication.class, args);
    }
}
