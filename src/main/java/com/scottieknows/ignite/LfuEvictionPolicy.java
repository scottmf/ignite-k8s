/**
 * Copyright (C) 2017 Scott Feldstein
 *
 * Permission is hereby granted, free of charge, to any person obtaining a 
 * copy of this software and associated documentation files (the "Software"), 
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.scottieknows.ignite;

import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.ignite.cache.eviction.AbstractEvictionPolicy;
import org.apache.ignite.cache.eviction.EvictableEntry;

/**
 * inspired by https://svn.apache.org/repos/asf/activemq/trunk/activemq-kahadb-store/src/main/java/org/apache/activemq/util/LFUCache.java
 */
public class LfuEvictionPolicy<K, V> extends AbstractEvictionPolicy<K, V> {

    private final AtomicInteger size = new AtomicInteger();
    private final LinkedHashSet[] frequencyList;
    private int lowestFrequency = 0;
    private int maxFrequency;

    public LfuEvictionPolicy(int maxSize) {
        setMaxSize(maxSize);
        this.frequencyList = new LinkedHashSet[maxSize];
        this.maxFrequency = maxSize - 1;
        initFrequencyList();
    }

    private void initFrequencyList() {
        for (int i = 0; i <= getMaxSize(); i++) {
            frequencyList[i] = new LinkedHashSet<EvictableEntry<K, V>>();
        }
    }

    @Override
    protected int getCurrentSize() {
        return size.get();
    }

    @Override
    protected int shrink0() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    protected boolean removeMeta(Object meta) {
        // TODO Auto-generated method stub
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean touch(EvictableEntry<K, V> entry) {
        CacheMeta meta = entry.meta();
        if (meta == null) {
            size.incrementAndGet();
            if (null != (meta = entry.putMetaIfAbsent(meta))) {
                touch(meta);
                return true;
            } else {
                LinkedHashSet<CacheMeta> nodes = frequencyList[0];
                K key = entry.getKey();
                int lowestFrequency = 0;
                meta = new CacheMeta<K>(key, lowestFrequency);
                nodes.add(meta);
                return true;
            }
        } else {
            touch(meta);
        }
        return false;
    }

    private void touch(CacheMeta meta) {
        int currentFrequency = meta.frequency.get();
        LinkedHashSet nodes = frequencyList[currentFrequency];
        if (currentFrequency < maxFrequency) {
            int nextFrequency = currentFrequency + 1;
            LinkedHashSet<CacheMeta<K>> currentNodes = frequencyList[currentFrequency];
            LinkedHashSet<CacheMeta<K>> newNodes = frequencyList[nextFrequency];
            moveToNextFrequency(meta, nextFrequency, currentNodes, newNodes);
            //cache.put((Key) k, currentNode);
            if (lowestFrequency == currentFrequency && currentNodes.isEmpty()) {
                lowestFrequency = nextFrequency;
            } else {
                // Hybrid with LRU: put most recently accessed ahead of others:
                nodes = frequencyList[currentFrequency];
                nodes.remove(meta);
                nodes.add(meta);
            }
        }
    }

    private void moveToNextFrequency(CacheMeta<K> meta, int nextFrequency, LinkedHashSet<CacheMeta<K>> currentNodes,
                                     LinkedHashSet<CacheMeta<K>> newNodes) {
        currentNodes.remove(meta);
        newNodes.add(meta);
        meta.frequency.set(nextFrequency);
    }

    private static class CacheMeta<K> {
        private AtomicInteger frequency;
        private K key;

        private CacheMeta(K key, int frequency) {
            this.frequency.set(frequency);
            this.key = key;
        }

        public int hashCode() {
            return key.hashCode();
        }

        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o instanceof CacheMeta) {
                CacheMeta cm = (CacheMeta) o;
                return cm.key.equals(key);
            }
            return false;
        }
    }

}
