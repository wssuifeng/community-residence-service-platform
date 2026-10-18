package com.community.residence.common.messaging.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实Spring事务管理器与替身连接验证时序，不访问数据库。 */
class AfterCommitDeliveryTest {
    final AfterCommitDeliveryService delivery = new AfterCommitDeliveryService();
    TransactionTemplate tx;
    @BeforeEach void setUp() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @Test void nonTransactionalDeliveryIsImmediate() {
        var count = new AtomicInteger(); delivery.deliver("test", count::incrementAndGet); assertThat(count.get()).isEqualTo(1);
    }
    @Test void successfulCommitDeliversExactlyOnceAfterBody() {
        var count = new AtomicInteger();
        tx.executeWithoutResult(s -> { delivery.deliver("test", count::incrementAndGet); assertThat(count.get()).isZero(); });
        assertThat(count.get()).isEqualTo(1);
    }
    @Test void localRollbackDoesNotDeliver() {
        var count = new AtomicInteger();
        tx.executeWithoutResult(s -> { delivery.deliver("test", count::incrementAndGet); s.setRollbackOnly(); });
        assertThat(count.get()).isZero();
    }
    @Test void outerRollbackDoesNotDeliverJoinedTransaction() {
        var count = new AtomicInteger();
        tx.executeWithoutResult(s -> { tx.executeWithoutResult(inner -> delivery.deliver("test", count::incrementAndGet)); s.setRollbackOnly(); });
        assertThat(count.get()).isZero();
    }
    @Test void exceptionRollsBackWithoutDelivery() {
        var count = new AtomicInteger();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> { delivery.deliver("test", count::incrementAndGet); throw new IllegalStateException("rollback"); })).isInstanceOf(IllegalStateException.class);
        assertThat(count.get()).isZero();
    }
    @Test void failedSendDoesNotTurnCommittedBusinessIntoFailure() {
        assertThatCode(() -> tx.executeWithoutResult(s -> delivery.deliver("test", () -> { throw new IllegalStateException("offline"); }))).doesNotThrowAnyException();
        assertThatCode(() -> delivery.deliver("test", () -> { throw new IllegalStateException("offline"); })).doesNotThrowAnyException();
    }
}
