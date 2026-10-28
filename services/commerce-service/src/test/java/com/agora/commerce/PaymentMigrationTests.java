package com.agora.commerce;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PaymentMigrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    @Test void v3KeepsPastDeadlineLegacyOrderAwaitingWithoutInventingCaptures()throws Exception {
        var old=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).defaultSchema("commerce").schemas("commerce").locations("classpath:db/migration").target("2").load();old.migrate();
        UUID actor=UUID.randomUUID(),preview=UUID.randomUUID(),key=UUID.randomUUID(),checkout=UUID.randomUUID(),order=UUID.randomUUID();
        try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())){
            try(var statement=connection.prepareStatement("INSERT INTO commerce.checkout_previews VALUES(?,?,?,?,'{}',clock_timestamp())")){statement.setObject(1,preview);statement.setObject(2,actor);statement.setObject(3,key);statement.setString(4,"a".repeat(64));statement.executeUpdate();}
            try(var statement=connection.prepareStatement("INSERT INTO commerce.checkout_attempts(actor_id,request_key,preview_id,checkout_id) VALUES(?,?,?,?)")){statement.setObject(1,actor);statement.setObject(2,key);statement.setObject(3,preview);statement.setObject(4,checkout);statement.executeUpdate();}
            try(var statement=connection.prepareStatement("INSERT INTO commerce.orders VALUES(?,?,?,'{}',clock_timestamp()-interval '1 day',clock_timestamp()-interval '1 hour',false)")){statement.setObject(1,order);statement.setObject(2,actor);statement.setObject(3,checkout);statement.executeUpdate();}
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).defaultSchema("commerce").schemas("commerce").locations("classpath:db/migration").load().migrate();
        try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var statement=connection.createStatement()){
            try(var result=statement.executeQuery("SELECT state,winner_capture_id,snapshot::text,expires_at<clock_timestamp() FROM commerce.orders")){assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo("AWAITING_PAYMENT");assertThat(result.getObject(2)).isNull();assertThat(result.getString(3)).isEqualTo("{}");assertThat(result.getBoolean(4)).isTrue();}
            try(var result=statement.executeQuery("SELECT count(*) FROM commerce.capture_applications")){assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isZero();}
        }
    }
}
