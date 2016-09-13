/**
 * Copyright 2011-2016 Asakusa Framework Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.asakusafw.dag.runtime.jdbc.basic;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asakusafw.dag.runtime.jdbc.JdbcOperationDriver;
import com.asakusafw.dag.runtime.jdbc.util.JdbcUtil;
import com.asakusafw.dag.utils.common.Arguments;
import com.asakusafw.dag.utils.common.InterruptibleIo.Closer;

/**
 * A basic implementation of {@link JdbcOperationDriver}.
 * @since 0.2.0
 */
public class BasicJdbcOperationDriver implements JdbcOperationDriver {

    static final Logger LOG = LoggerFactory.getLogger(BasicJdbcOperationDriver.class);

    private final String sql;

    /**
     * Creates a new instance.
     * @param sql the delete statement
     */
    public BasicJdbcOperationDriver(String sql) {
        Arguments.requireNonNull(sql);
        this.sql = sql;
    }

    @Override
    public void perform(Connection connection) throws IOException, InterruptedException {
        LOG.debug("JDBC operation: {}", sql); //$NON-NLS-1$
        try (Closer closer = new Closer()) {
            Statement statement = connection.createStatement();
            closer.add(JdbcUtil.wrap(statement::close));
            statement.execute(sql);
            LOG.debug("commit: {}", sql); //$NON-NLS-1$
            connection.commit();
        } catch (SQLException e) {
            throw JdbcUtil.wrap(e);
        }
    }
}