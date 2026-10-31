package org.aristonis.diwan.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.aristonis.diwan.database.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcPoolSettingsTest {

    @TempDir
    Path folder;

    @Test
    void keepsWorkDoneOutsideATransactionWhenThePoolHandsOutAutoCommitOff() {
        String file = folder.resolve("pool.db").toString();
        CountingDataSource pool = new CountingDataSource(file).withoutAutoCommit();
        Connection db = Connection.builder().name("pool").dataSource(pool).build();

        db.statement("create table t (id integer)");
        db.insert("insert into t values (1)");
        db.transaction(tx -> tx.insert("insert into t values (2)"));

        Connection reader = Connection.builder().name("reader").dataSource(new CountingDataSource(file)).build();
        assertEquals(2, ((Number) reader.scalar("select count(*) from t").orElseThrow()).intValue());
        assertEquals(0, pool.openConnections());
        assertEquals(0, pool.returnedWithoutAutoCommit());
    }

    @Test
    void refusesToStartOrEndATransactionItDoesNotOwn() throws Exception {
        CountingDataSource source = new CountingDataSource(folder.resolve("ext.db").toString());
        java.sql.Connection outer = source.getConnection();
        outer.setAutoCommit(false);
        JdbcConnectionProvider external = new JdbcConnectionProvider() {
            @Override
            public java.sql.Connection acquire() {
                return outer;
            }

            @Override
            public void release(java.sql.Connection connection) {
                // owned by the outer transaction
            }

            @Override
            public boolean inExternalTransaction(java.sql.Connection connection) {
                return true;
            }
        };
        JdbcSession session = (JdbcSession) new JdbcDriver(external).open();

        assertThrows(IllegalStateException.class, session::begin);
        assertThrows(IllegalStateException.class, session::commit);
        assertThrows(IllegalStateException.class, session::rollback);
        session.close();
        outer.rollback();
        outer.setAutoCommit(true);
        outer.close();
    }
}
