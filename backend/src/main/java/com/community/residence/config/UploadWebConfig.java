package com.community.residence.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/** 上传文件静态访问：磁盘 upload.dir 目录映射到 /uploads/**（FileUploadService 写入侧） */
@Configuration
public class UploadWebConfig implements WebMvcConfigurer {

    private final String uploadDir;

    public UploadWebConfig(@Value("${upload.dir:./uploads}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
