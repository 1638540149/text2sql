package com.text2sql.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SqlSafetyTest {
    @Test
    void validateSelectAcceptsSingleSelectAndExtractsTables() {
        SqlSafety.ParsedSql parsed = SqlSafety.validateSelect(
            "SELECT o.id, c.name FROM orders o JOIN customers c ON c.id = o.customer_id"
        );

        assertThat(parsed.referencedTables()).containsExactly("orders", "customers");
    }

    @Test
    void validateSelectRejectsNonSelectSql() {
        assertThatThrownBy(() -> SqlSafety.validateSelect("UPDATE orders SET amount = 1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("非只读");
    }

    @Test
    void validateSelectRejectsInvalidSqlBeforeExecution() {
        assertThatThrownBy(() -> SqlSafety.validateSelect("SELECT FROM"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SQL parser");
    }

    @Test
    void validateSelectRejectsMultiStatementSql() {
        assertThatThrownBy(() -> SqlSafety.validateSelect("SELECT * FROM orders; SELECT * FROM customers"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("多语句");
    }
}
