package com.community.residence.common.reservation.service;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ResidentScheduleLockTest {
    final RedissonClient client = mock(RedissonClient.class);
    final RLock lock = mock(RLock.class);
    void ready() throws Exception { when(client.getLock(anyString())).thenReturn(lock); when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true); }
    TransactionTemplate tx() throws Exception {
        var ds = mock(DataSource.class); var connection = mock(Connection.class);
        when(ds.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        return new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @Test void sharedResidentDateKeyAndAutoCommitRelease() throws Exception {
        ready(); var date = LocalDate.now();
        assertThat(ResidentScheduleLock.execute(client, 7L, date, () -> 42)).isEqualTo(42);
        verify(client).getLock("reservation:lock:resident:7:" + date); verify(lock).unlock();
    }
    @Test void commitKeepsLockThroughBusinessAndReleasesAfterwards() throws Exception {
        ready(); tx().executeWithoutResult(s -> { ResidentScheduleLock.execute(client, 7L, LocalDate.now(), () -> 42); verify(lock, never()).unlock(); });
        verify(lock).unlock();
    }
    @Test void rollbackAlsoReleasesLock() throws Exception {
        ready(); tx().executeWithoutResult(s -> { ResidentScheduleLock.execute(client, 7L, LocalDate.now(), () -> 42); s.setRollbackOnly(); verify(lock, never()).unlock(); });
        verify(lock).unlock();
    }
    @Test void unavailableLockNeverExecutesWrites() throws Exception {
        ready(); when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(false); var action = mock(java.util.function.Supplier.class);
        assertThatThrownBy(() -> ResidentScheduleLock.execute(client, 7L, LocalDate.now(), action)).isInstanceOf(com.community.residence.common.web.exception.BusinessException.class);
        verifyNoInteractions(action); verify(lock, never()).unlock();
    }
}
