package com.medirecords.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mediRecordsOpenApi(@Value("${app.version:dev}") String version) {
        return new OpenAPI().info(new Info()
                .title("MediRecords API")
                .description("Patient records management: CRUD, bulk CSV import and audit log.")
                .version(version));
    }
}
