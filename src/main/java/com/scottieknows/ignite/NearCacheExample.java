/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scottieknows.ignite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.cache.Cache.Entry;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteException;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.eviction.igfs.IgfsPerBlockLruEvictionPolicy;
import org.apache.ignite.cache.eviction.lru.LruEvictionPolicy;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.cluster.ClusterGroup;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.NearCacheConfiguration;
import org.apache.ignite.events.EventType;
import org.apache.ignite.lang.IgniteBiPredicate;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.multicast.TcpDiscoveryMulticastIpFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates new functional APIs.
 * <p>
 * Remote nodes should always be started with special configuration file which
 * enables P2P class loading: {@code 'ignite.{sh|bat} examples/config/example-ignite.xml'}.
 * <p>
 * Alternatively you can run {@link ExampleNodeStartup} in another JVM which will start node
 * with {@code examples/config/example-ignite.xml} configuration.
 */
public class NearCacheExample {
    private static final Logger logger = LoggerFactory.getLogger(NearCacheExample.class);

    /**
     * Executes example.
     *
     * @param args Command line arguments, none required.
     * @throws IgniteException If example execution failed.
     * @throws InterruptedException 
     */
    public static void main(String[] args) throws IgniteException, InterruptedException {
        Ignition.setClientMode(true);
        IgniteConfiguration igniteConfiguration = new IgniteConfiguration();
        igniteConfiguration.setIgniteHome(System.getenv("IGNITE_HOME"));
        igniteConfiguration.setIncludeEventTypes(EventType.EVTS_ALL);
        igniteConfiguration.setPeerClassLoadingEnabled(true);
//        igniteConfiguration.setClientFailureDetectionTimeout(clientFailureDetectionTimeout);
        TcpDiscoverySpi tcpDiscoverySpi = new TcpDiscoverySpi();
        igniteConfiguration.setDiscoverySpi(tcpDiscoverySpi);
        TcpDiscoveryIpFinder podResolver = getKubePodResolver();
//        TcpDiscoveryIpFinder podResolver = getTcpPodResolver();
        tcpDiscoverySpi.setIpFinder(podResolver);
        tcpDiscoverySpi.setJoinTimeout(30000);
        tcpDiscoverySpi.setAckTimeout(30000);
        tcpDiscoverySpi.setSocketTimeout(30000);
        tcpDiscoverySpi.setNetworkTimeout(30000);
        tcpDiscoverySpi.failureDetectionTimeoutEnabled(true);
        TimeUtils timeUtils = new TimeUtils();
        long start = timeUtils.now();
        logger.info("ignite starting");
        try (Ignite ignite = Ignition.start(igniteConfiguration)) {
            logger.info("ignite started in {} ms", timeUtils.now() - start);
            while (ignite.cluster().forClients().nodes().size() != 2) {
                logger.info("waiting for 2 clients size={}", ignite.cluster().forClients().nodes().size());
                timeUtils.sleep(100);
            }
            //ignite.cluster().forClients().
//            ignite.cluster().forClients().node
            UUID localUuid = ignite.cluster().localNode().id();
            ClusterGroup clients = ignite.cluster().forClients();
            ArrayList<UUID> list = clients.nodes().stream()
                                          .sorted((a, b) -> a.id().compareTo(b.id()))
                                          .map(n -> n.id())
                                          .collect(Collectors.toCollection(ArrayList::new));
            boolean low = list.get(0).equals(localUuid) ? true : false;

            // Create near-cache configuration for "myCache".
            NearCacheConfiguration<Integer, Integer> nearCfg = new NearCacheConfiguration<>();

            // Use LRU eviction policy to automatically evict entries
            // from near-cache, whenever it reaches 100_000 in size.
            nearCfg.setNearEvictionPolicy(new LruEvictionPolicy<>(5000));
            nearCfg.setNearStartSize(6000);
            Random rand = new Random(System.currentTimeMillis());

//            if (low) {
//                logger.info("creating cache myCache");
//                ignite.destroyCache("myCache");
//            } else {
//                logger.info("sleeping");
//                timeUtils.sleep(15000);
//            }
            // Create a distributed cache on server nodes and 
            // a near cache on the local node, named "myCache".
            CacheConfiguration<Integer, Integer> cacheConfiguration = new CacheConfiguration<Integer, Integer>("myCache");
            cacheConfiguration.setOnheapCacheEnabled(false);
            // can't set an eviction policy if off heap cache is used
            if (cacheConfiguration.isOnheapCacheEnabled()) {
                cacheConfiguration.setEvictionPolicy(new LruEvictionPolicy<>(10000));
//            } else {
//                cacheConfiguration.setEvictionPolicy(new IgfsPerBlockLruEvictionPolicy(1024*1024*1024*10, 512));
            }
//            cacheConfiguration.setAffinityMapper(affMapper);
//            GridCacheWriteBehindStore<? super Integer, ? super Integer> storeFactory =
//                new GridCacheWriteBehindStore<>(storeMgr, igniteInstanceName, cacheName, log, store);
//            cacheConfiguration.setCacheStoreFactory(storeFactory);
            cacheConfiguration.setStatisticsEnabled(true);
//            cacheConfiguration.setWriteBehindEnabled(true);
            cacheConfiguration.setCacheMode(CacheMode.PARTITIONED);
//            cacheConfiguration.setQueryParallelism(2);
            cacheConfiguration.setQueryParallelism(3);
//            cacheConfiguration.setCacheMode(CacheMode.REPLICATED);
            cacheConfiguration.setBackups(2);
//            cacheConfiguration.setCacheWriterFactory(new );
//            cacheConfiguration.setIndexedTypes(Integer.class, Integer.class);
//            cacheConfiguration.setWriteBehindFlushFrequency(writeBehindFlushFreq);
//            cacheConfiguration.setWriteBehindFlushThreadCount(writeBehindFlushThreadCnt);
            logger.info("creating cache with configuration: {}", cacheConfiguration);
            IgniteCache<Integer, Integer> cache = ignite.getOrCreateCache(cacheConfiguration, nearCfg);
            StringBuilder buf = new StringBuilder();
            start = System.currentTimeMillis();
            int max = low ? 6000 : 10000;
            int min = low ? 0 : 4000;
//            if (low) {
//                for (int i=0; i<5000; i++) {
//                    cache.put(i, i);
//                }
//            } else {
//                for (int i=5000; i<10000; i++) {
//                    cache.put(i, i);
//                }
//            }
//            int max = low ? 5000 : 10000;
//            int min = low ? 0 : 5000;
//            for (int i=min; i<max; i++) {
//                buf.append(cache.get(i) + ",");
//            }
//            logger.info("low={} time to load cache={} ms", low, (timeUtils.now() - start));
            logger.info("low={}", low);

            for (int i=0; i<1000; i++) {
                runLoad(timeUtils, rand, cache, min, max);
            }

            logger.info("system metrics: {}", cache.metrics().toString());
            logger.info("local metrics: {}", cache.localMetrics().toString());
            long startKeys = System.currentTimeMillis();
            @SuppressWarnings("serial")
            List<Entry<Integer, Integer>> all = cache.query(new ScanQuery<>(new IgniteBiPredicate<Integer, Integer>() {
                @Override
                public boolean apply(Integer e1, Integer e2) {
                    return true;
                }
            })).getAll();
            logger.info("time to run getKeys={} ms", (timeUtils.now() - startKeys));
            logger.info("time to run={} ms", (timeUtils.now() - start));
//            buf.setLength(0);
//            all.stream().sorted((a, b) -> a.getKey().compareTo(b.getKey())).forEach(entry -> {
//                buf.append(entry.getKey() + ",");
//            });
//            logger.info(buf.toString());

            Thread.currentThread().join();
        } catch (Exception e) {
            logger.error("problem starting ignite client: {}", e.getMessage(),e);
            System.exit(0);
        }
    }

    private static TcpDiscoveryIpFinder getTcpPodResolver() {
        TcpDiscoveryMulticastIpFinder tcpDiscoveryMulticastIpFinder = new TcpDiscoveryMulticastIpFinder();
        tcpDiscoveryMulticastIpFinder.setAddresses(Collections.singletonList("127.0.0.1:47500..47509"));
        return tcpDiscoveryMulticastIpFinder;
    }

    private static TcpDiscoveryIpFinder getKubePodResolver() {
        IgnitePodResolver ignitePodResolver = new IgnitePodResolver();
        ignitePodResolver.setKubeMaster("https://kubernetes.default.svc.cluster.local:443");
        ignitePodResolver.setAppLabel("ignite");
        ignitePodResolver.init();
        return ignitePodResolver;
    }

    private static void runLoad(TimeUtils timeUtils, Random rand, IgniteCache<Integer, Integer> cache, int min, int max) {
        long start = timeUtils.now();
        long s = timeUtils.now();
        putRand(rand, cache, min, max);
        logger.info("time to run puts={} ms, min={}, max={}", (timeUtils.now() - s), min, max);
        s = timeUtils.now();
        getRand(rand, cache, min, max);
        logger.info("time to run gets={} ms, min={}, max={}", (timeUtils.now() - s), min, max);
        s = timeUtils.now();
        getRand(rand, cache, min, max);
        logger.info("time to run gets={} ms, min={}, max={}", (timeUtils.now() - s), min, max);
        s = timeUtils.now();
        getRand(rand, cache, min, max);
        logger.info("time to run gets={} ms, min={}, max={}", (timeUtils.now() - s), min, max);
        logger.info("time to run ops={} ms, min={}, max={}", (timeUtils.now() - start), min, max);
    }

    private static void putRand(Random rand, IgniteCache<Integer, Integer> cache, int min, int max) {
        for (int i=min; i<max; i++) {
            cache.put(rand.nextInt(max - min) + min, rand.nextInt(max));
        }
    }

    private static void getRand(Random rand, IgniteCache<Integer, Integer> cache, int min, int max) {
        for (int i=min; i<max; i++) {
            cache.get(rand.nextInt(max - min) + min);
        }
    }

}
