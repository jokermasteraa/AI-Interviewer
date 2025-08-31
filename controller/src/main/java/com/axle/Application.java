package com.axle;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.axle.mapper")
public class Application {
    public static void main(String[] args) {
        System.setProperty("pagehelper.banner","false");
        SpringApplication.run(Application.class, args);
    }
}