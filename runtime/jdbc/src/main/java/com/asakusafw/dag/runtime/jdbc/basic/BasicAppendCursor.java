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
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asakusafw.dag.api.processor.ObjectWriter;
import com.asakusafw.dag.runtime.jdbc.PreparedStatementAdapter;
import com.asakusafw.dag.runtime.jdbc.util.JdbcUtil;
import com.asakusafw.dag.utils.common.Arguments;
import com.asakusafw.dag.utils.common.InterruptibleIo;

class BasicAppendCursor implements ObjectWriter {

    static final Logger LOG = LoggerFactory.getLogger(BasicAppendCursor.class);

    private final PreparedStatement statement;

    private final PreparedStatementAdapter<Object> adapter;

    private final int windowSize;

    private final InterruptibleIo resource;

    private int restWindowSize;

    private boolean sawError = false;

    /**
     * Creates a new instance.
     * @param statement the related statement
     * @param adapter the JDBC support object
     * @param windowSize the batch append window size
     * @param resource the resources to be closed on this object was closed
     */
    @SuppressWarnings("unchecked")
    BasicAppendCursor(
            PreparedStatement statement, PreparedStatementAdapter<?> adapter,
            int windowSize,
            InterruptibleIo resource) {
        Arguments.requireNonNull(statement);
        Arguments.requireNonNull(adapter);
        Arguments.require(windowSize >= 1);
        this.adapter = (PreparedStatementAdapter<Object>) adapter;
        this.statement = statement;
        this.windowSize = windowSize;
        this.resource = resource;
        this.restWindowSize = windowSize;
    }

    @Override
    public void putObject(Object object) throws IOException, InterruptedException {
        try {
            adapter.drive(statement, object);
            statement.addBatch();
        } catch (SQLException e) {
            this.sawError = true;
            throw JdbcUtil.wrap(e);
        }
        restWindowSize--;
        if (restWindowSize == 0) {
            flush();
        }
        assert restWindowSize > 0;
    }

    private void flush() throws IOException {
        if (windowSize == restWindowSize) {
            return;
        }
        if (LOG.isTraceEnabled()) {
            LOG.trace("commit {} records", windowSize - restWindowSize); //$NON-NLS-1$
        }
        restWindowSize = windowSize;
        try {
            statement.executeBatch();
            statement.getConnection().commit();
        } catch (SQLException e) {
            this.sawError = true;
            throw JdbcUtil.wrap(e);
        }
    }

    @Override
    public void close() throws IOException, InterruptedException {
        try {
            if (sawError == false) {
                flush();
            }
        } finally {
            if (resource != null) {
                resource.close();
            }
        }
    }
}
