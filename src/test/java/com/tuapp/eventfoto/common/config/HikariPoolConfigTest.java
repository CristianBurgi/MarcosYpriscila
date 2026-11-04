package com.tuapp.eventfoto.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class HikariPoolConfigTest {

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("El pool de Hikari tiene máximo 20 y mínimo en reposo 2 (sin minimum-idle quedaban 20 conexiones abiertas siempre)")
    void poolHasMaximum20AndMinimumIdle2() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        assertThat(hikari.getMaximumPoolSize()).isEqualTo(20);
        assertThat(hikari.getMinimumIdle()).isEqualTo(2);
    }
}
