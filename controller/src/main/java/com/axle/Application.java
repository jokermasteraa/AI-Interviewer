package com.axle;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.axle.mapper")
public class Application {

    @Autowired
    private VectorStore vectorStore;

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    public void run(String... args) throws Exception {
        if (vectorStore != null) {
            System.out.println("VectorStore bean successfully registered: " + vectorStore.getClass().getName());
        } else {
            System.err.println("VectorStore bean not found!");
        }
    }

}