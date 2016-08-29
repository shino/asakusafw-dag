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
package com.asakusafw.dag.runtime.jdbc.operation;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asakusafw.dag.api.processor.ProcessorContext;
import com.asakusafw.dag.api.processor.ProcessorContext.Editor;
import com.asakusafw.dag.api.processor.extension.ProcessorContextExtension;
import com.asakusafw.dag.runtime.jdbc.ConnectionPool;
import com.asakusafw.dag.runtime.jdbc.JdbcProfile;
import com.asakusafw.dag.runtime.jdbc.basic.BasicConnectionPool;
import com.asakusafw.dag.utils.common.InterruptibleIo;
import com.asakusafw.dag.utils.common.InterruptibleIo.Closer;
import com.asakusafw.dag.utils.common.Optionals;
import com.asakusafw.dag.utils.common.Tuple;

/**
 * Installs {@link JdbcEnvironment} into the core processor environment.
 * @since 0.2.0
 */
public class JdbcEnvironmentInstaller implements ProcessorContextExtension {

    /**
     * The property key prefix.
     * Each property key must be in form of {@code [prefix].[profile-name].[sub-key]}.
     */
    public static final String KEY_PREFIX = "com.asakusafw.dag.jdbc.";

    /**
     * The property sub-key of connection URL.
     */
    public static final String KEY_URL = "url";

    /**
     * The property sub-key of fqn of JDBC Driver.
     */
    public static final String KEY_DRIVER = "driver";

    /**
     * The property sub-key of JDBC connection properties.
     */
    public static final String KEY_PROPERTIES = "properties";

    /**
     * The property sub-key of the max number of connections.
     */
    public static final String KEY_POOL_SIZE = "connection.max";

    /**
     * The property sub-key of {@link ResultSet#getFetchSize() fetch size}.
     */
    public static final String KEY_FETCH_SIZE = "input.records";

    /**
     * The property sub-key of the number of threads per input.
     */
    public static final String KEY_INPUT_THREADS = "input.threads";

    /**
     * The property sub-key of {@link PreparedStatement#executeBatch() the number of batch insert records} per commit.
     */
    public static final String KEY_BATCH_INSERT_SIZE = "output.records";

    /**
     * The property sub-key of the number of threads per output.
     */
    public static final String KEY_OUTPUT_THREADS = "output.threads";

    /**
     * The default value of {@link #KEY_POOL_SIZE}.
     */
    public static final int DEFAULT_POOL_SIZE = 1;

    /**
     * The default value of {@link #KEY_FETCH_SIZE}.
     */
    public static final int DEFAULT_FETCH_SIZE = 1024;

    /**
     * The default value of {@link #KEY_BATCH_INSERT_SIZE}.
     */
    public static final int DEFAULT_BATCH_INSERT_SIZE = 1024;

    /**
     * The default value of {@link #KEY_INPUT_THREADS}.
     */
    public static final int DEFAULT_INPUT_THREADS = 1;

    /**
     * The default value of {@link #KEY_OUTPUT_THREADS}.
     */
    public static final int DEFAULT_OUTPUT_THREADS = 1;

    private static final Pattern PATTERN_KEY = Pattern.compile(Pattern.quote(KEY_PREFIX) + "(\\w+)\\.(.+)"); //$NON-NLS-1$

    static final Logger LOG = LoggerFactory.getLogger(JdbcEnvironmentInstaller.class);

    @Override
    public InterruptibleIo install(ProcessorContext context, Editor editor) throws IOException, InterruptedException {
        try (Closer closer = new Closer()) {
            List<JdbcProfile> profiles = collect(context, closer);
            editor.addResource(JdbcEnvironment.class, new JdbcEnvironment(profiles));
            return closer.move();
        }
    }

    private static List<JdbcProfile> collect(ProcessorContext context, Closer closer) {
        Map<String, Map<String, String>> properties = getProfiles(context.getPropertyMap());
        List<JdbcProfile> results = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : properties.entrySet()) {
            String profileName = entry.getKey();
            LOG.debug("loading JDBC profile: {}", profileName);
            Map<String, String> subProperties = entry.getValue();
            results.add(resolve(context, profileName, subProperties, closer));
        }
        return results;
    }

    private static JdbcProfile resolve(
            ProcessorContext context,
            String profileName, Map<String, String> properties,
            Closer closer) {
        Optionals.remove(properties, KEY_DRIVER).ifPresent(name -> {
            try {
                Class.forName(name, true, context.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException(MessageFormat.format(
                        "failed to load JDBC driver class: {1} ({0})",
                        qualified(profileName, KEY_DRIVER),
                        name));
            }
        });
        String url = extract(profileName, properties, KEY_URL);
        int maxConnections = extract(profileName, properties, KEY_POOL_SIZE, DEFAULT_POOL_SIZE);
        Map<String, String> connectionProps = extractMap(profileName, properties, KEY_PROPERTIES);
        int fetchSize = extract(profileName, properties, KEY_FETCH_SIZE, DEFAULT_FETCH_SIZE);
        int insertSize = extract(profileName, properties, KEY_BATCH_INSERT_SIZE, DEFAULT_BATCH_INSERT_SIZE);
        int fetchThreads = extract(profileName, properties, KEY_INPUT_THREADS, DEFAULT_INPUT_THREADS);
        int insertThreads = extract(profileName, properties, KEY_OUTPUT_THREADS, DEFAULT_OUTPUT_THREADS);
        ConnectionPool connections = closer.add(new BasicConnectionPool(url, connectionProps, maxConnections));
        if (LOG.isDebugEnabled()) {
            LOG.debug("JDBC profile: name={}, jdbc={}@{}, fetch={}@{}, put={}@{}", new Object[] {
                    profileName,
                    url, maxConnections,
                    fetchSize, insertThreads,
                    insertSize, insertThreads,
            });
        }
        if (properties.isEmpty() == false) {
            throw new IllegalArgumentException(MessageFormat.format(
                    "unrecognized JDBC profile properties: {0}",
                    properties.keySet().stream()
                        .map(k -> qualified(profileName, k))
                        .collect(Collectors.joining())));
        }
        return new JdbcProfile(profileName, connections, fetchSize, insertSize, fetchThreads, insertThreads);
    }

    private static Map<String, Map<String, String>> getProfiles(Map<String, String> flat) {
        return flat.entrySet().stream()
                .map(Tuple::of)
                .filter(e -> e.left() != null)
                .flatMap(t -> {
                    Matcher matcher = PATTERN_KEY.matcher(t.left());
                    if (matcher.matches()) {
                        String profile = matcher.group(1);
                        String subKey = matcher.group(2);
                        return Stream.of(new Tuple<>(profile, new Tuple<>(subKey, t.right())));
                    } else {
                        return Stream.empty();
                    }
                })
                .collect(Collectors.groupingBy(
                        Tuple::left,
                        Collectors.mapping(Tuple::right, Collectors.toMap(Tuple::left, Tuple::right))));
    }

    private static String extract(String profile, Map<String, String> properties, String key) {
        return Optionals.remove(properties, key)
                .orElseThrow(() -> new IllegalArgumentException(MessageFormat.format(
                        "missing mandatory property: {0}",
                        qualified(profile, key))));
    }

    private static int extract(String profile, Map<String, String> properties, String key, int defaultValue) {
        return Optionals.remove(properties, key)
                .map(v -> {
                    try {
                        return Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(MessageFormat.format(
                                "\"{0}\" must be a valid integer: {1}",
                                qualified(profile, key), v));
                    }
                })
                .orElse(defaultValue);
    }

    private static Map<String, String> extractMap(String profile, Map<String, String> properties, String key) {
        Pattern pattern = Pattern.compile(Pattern.quote(key) + "\\.(.+)"); //$NON-NLS-1$
        Map<String, String> results = properties.entrySet().stream()
                .map(Tuple::of)
                .flatMap(t -> {
                    Matcher matcher = pattern.matcher(t.left());
                    if (matcher.matches()) {
                        String subKey = matcher.group(1);
                        return Stream.of(new Tuple<>(subKey, t.right()));
                    } else {
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toMap(Tuple::left, Tuple::right));
        for (Iterator<String> iter = properties.keySet().iterator(); iter.hasNext();) {
            String next = iter.next();
            if (next != null && pattern.matcher(next).matches()) {
                iter.remove();
            }
        }
        return results;
    }

    static String qualified(String profile, String key) {
        return String.format("%s%s.%s", KEY_PREFIX, profile, key); //$NON-NLS-1$
    }
}
