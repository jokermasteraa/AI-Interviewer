package com.axle.controller;

import com.axle.base.MinIOConfig;
import com.axle.base.MinIOUtils;
import com.axle.graceresult.GraceJSONResult;
import com.axle.graceresult.ResponseStatusEnum;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;


import java.util.UUID;

@RestController
@RequestMapping("/file")
public class filecontroller {

    @Resource
    private MinIOConfig minioConfig;

    @PostMapping("/uploadInterviewerImage")
    public GraceJSONResult uploadInterviewerImage(@RequestParam("file") MultipartFile file) throws Exception {
        //获取原始文件名
        String originalFilename = file.getOriginalFilename();
        if(StringUtils.isBlank(originalFilename)){
            //为空返回错误信息
            return GraceJSONResult.errorCustom(ResponseStatusEnum.FILE_UPLOAD_FAILD);
        }
        //重新命名
        String fileName = dealWithoutFilename(originalFilename);
        //文件上传
        String imageUrl = MinIOUtils.uploadFile(minioConfig.getBucketName(),
                fileName,
                file.getInputStream(),
                true);
        return GraceJSONResult.ok(imageUrl);
    }

    private String dealWithFilename(String originalFilename){
        //获得文件名后缀
        String suffixName = originalFilename.substring((originalFilename.lastIndexOf(".")));
        //获得文件名
        String fileName = originalFilename.substring(0,originalFilename.lastIndexOf("."));
        //生成随机ID
        String uuid = UUID.randomUUID().toString();
        return fileName+"-"+uuid+suffixName;
    }

    private String dealWithoutFilename(String originalFilename){
        //获得文件名后缀
        String suffixName = originalFilename.substring((originalFilename.lastIndexOf(".")));
        //生成随机ID
        String uuid = UUID.randomUUID().toString();
        return uuid+suffixName;
    }
}
