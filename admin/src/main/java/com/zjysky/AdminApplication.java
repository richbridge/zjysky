package com.zjysky;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 持久层（entity + mapper）统一由 common 模块提供，这里显式扫描注册，
 * 让 common 的 Md*Mapper 系列接口成为 Spring Bean（接口本身带 @Mapper 注解）。
 */
@SpringBootApplication
@MapperScan("com.zjysky.common.database.mapper")
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }

}
