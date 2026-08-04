package com.sushimei.sushimei.backend.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Mapea la URL /uploads/** a tu carpeta física local
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:C:/sushimei/uploads/");
    }
}
