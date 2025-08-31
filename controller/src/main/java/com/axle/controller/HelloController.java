package com.axle.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
    /**
     * 测试的controller方法
     * @return
     */
    @GetMapping("hello")
    public Object hello(){
        return  "Hello World";

    }
}
