/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.index.TransportIndexAction;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.AutoCreateIndex;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.transport.local.LocalTransport;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.elasticsearch.test.ClusterServiceUtils.createClusterService;
import static org.hamcrest.CoreMatchers.instanceOf;

public class DynamicMappingDisabledTests extends ESSingleNodeTestCase {

    private static ThreadPool threadPool;
    private ClusterService clusterService;
    private LocalTransport transport;
    private TransportService transportService;
    private IndicesService indicesService;
    private ShardStateAction shardStateAction;
    private ActionFilters actionFilters;
    private IndexNameExpressionResolver indexNameExpressionResolver;
    private AutoCreateIndex autoCreateIndex;
    private Settings settings;

    @BeforeClass
    public static void createThreadPool() {
        threadPool = new TestThreadPool("DynamicMappingDisabledTests");
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        settings = Settings.builder()
                .put(MapperService.INDEX_MAPPER_DYNAMIC_SETTING.getKey(), false)
                .build();
        clusterService = createClusterService(threadPool);
        transport = new LocalTransport(settings, threadPool, new NamedWriteableRegistry(Collections.emptyList()),
                    new NoneCircuitBreakerService());
        transportService = new TransportService(clusterService.getSettings(), transport, threadPool,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR, x -> clusterService.localNode(), null);
        indicesService = getInstanceFromNode(IndicesService.class);
        shardStateAction = new ShardStateAction(settings, clusterService, transportService, null, null, threadPool);
        actionFilters = new ActionFilters(Collections.emptySet());
        indexNameExpressionResolver = new IndexNameExpressionResolver(settings);
        autoCreateIndex = new AutoCreateIndex(settings, new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS),
                indexNameExpressionResolver);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        clusterService.close();
        transportService.close();
    }


    @AfterClass
    public static void destroyThreadPool() {
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        // since static must set to null to be eligible for collection
        threadPool = null;
    }

    public void testDynamicDisabled() {
        TransportIndexAction action = new TransportIndexAction(settings, transportService, clusterService,
                indicesService, null, threadPool, shardStateAction, null, null, actionFilters, indexNameExpressionResolver,
                autoCreateIndex);

        IndexRequest request = new IndexRequest("index", "type", "1");
        request.source("foo", 3);
        final AtomicBoolean onFailureCalled = new AtomicBoolean();

        action.execute(request, new ActionListener<IndexResponse>() {
            @Override
            public void onResponse(IndexResponse indexResponse) {
                fail("onResponse shouldn't be called");
            }

            @Override
            public void onFailure(Exception e) {
                onFailureCalled.set(true);
                assertThat(e, instanceOf(IndexNotFoundException.class));
                assertEquals("no such index and [index.mapper.dynamic] is [false]", e.getMessage());
            }
        });

        assertTrue(onFailureCalled.get());
    }
}
