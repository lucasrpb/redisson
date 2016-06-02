/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.core.RFuture;
import org.redisson.core.RSetMultimapCache;

/**
 * @author Nikita Koksharov
 *
 * @param <K> key
 * @param <V> value
 */
public class RedissonSetMultimapCache<K, V> extends RedissonSetMultimap<K, V> implements RSetMultimapCache<K, V> {

    private final RedissonMultimapCache<K> baseCache;
    
    RedissonSetMultimapCache(EvictionScheduler evictionScheduler, CommandAsyncExecutor connectionManager, String name) {
        super(connectionManager, name);
        evictionScheduler.scheduleCleanMultimap(name, getTimeoutSetName());
        baseCache = new RedissonMultimapCache<K>(connectionManager, name, codec, getTimeoutSetName());
    }

    RedissonSetMultimapCache(EvictionScheduler evictionScheduler, Codec codec, CommandAsyncExecutor connectionManager, String name) {
        super(codec, connectionManager, name);
        evictionScheduler.scheduleCleanMultimap(name, getTimeoutSetName());
        baseCache = new RedissonMultimapCache<K>(connectionManager, name, codec, getTimeoutSetName());
    }

    public RFuture<Boolean> containsKeyAsync(Object key) {
        try {
            byte[] keyState = codec.getMapKeyEncoder().encode(key);
            String keyHash = hash(keyState);

            String setName = getValuesName(keyHash);
            
            return commandExecutor.evalReadAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN,
                    "local value = redis.call('hget', KEYS[1], ARGV[2]); " +
                    "if value ~= false then " +
                          "local expireDate = 92233720368547758; " +
                          "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); "
                        + "if expireDateScore ~= false then "
                            + "expireDate = tonumber(expireDateScore) "
                        + "end; "
                        + "if expireDate <= tonumber(ARGV[1]) then "
                            + "return 0;"
                        + "end; "
                        + "return redis.call('scard', ARGV[3]) > 0 and 1 or 0;" +
                    "end;" +
                    "return 0; ",
                   Arrays.<Object>asList(getName(), getTimeoutSetName()), System.currentTimeMillis(), keyState, setName);
            
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    String getTimeoutSetName() {
        return "redisson_set_multimap_ttl{" + getName() + "}";
    }


    public RFuture<Boolean> containsValueAsync(Object value) {
        try {
            byte[] valueState = codec.getMapValueEncoder().encode(value);

            return commandExecutor.evalReadAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN,
                    "local keys = redis.call('hgetall', KEYS[1]); " +
                    "for i, v in ipairs(keys) do " +
                        "if i % 2 == 0 then " +
                            "local expireDate = 92233720368547758; " +
                            "local expireDateScore = redis.call('zscore', KEYS[2], keys[i-1]); "
                          + "if expireDateScore ~= false then "
                              + "expireDate = tonumber(expireDateScore) "
                          + "end; "
                          + "if expireDate > tonumber(ARGV[2]) then " +
                              "local name = '{' .. KEYS[1] .. '}:' .. v; " +
                              "if redis.call('sismember', name, ARGV[1]) == 1 then "
                              + "return 1; " +
                              "end;" +
                            "end; " +
                        "end;" +
                    "end; " +
                    "return 0; ",
                    Arrays.<Object>asList(getName(), getTimeoutSetName()), valueState, System.currentTimeMillis());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public RFuture<Boolean> containsEntryAsync(Object key, Object value) {
        try {
            byte[] keyState = codec.getMapKeyEncoder().encode(key);
            String keyHash = hash(keyState);
            byte[] valueState = codec.getMapValueEncoder().encode(value);

            String setName = getValuesName(keyHash);
            return commandExecutor.evalReadAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN,
                    "local expireDate = 92233720368547758; " +
                    "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); "
                  + "if expireDateScore ~= false then "
                      + "expireDate = tonumber(expireDateScore) "
                  + "end; "
                  + "if expireDate > tonumber(ARGV[1]) then " +
                      "if redis.call('sismember', KEYS[1], ARGV[3]) == 1 then "
                      + "return 1; " +
                      "end;" +
                    "end; " +
                    "return 0; ",
                    Arrays.<Object>asList(setName, getTimeoutSetName()), System.currentTimeMillis(), keyState, valueState);
            
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<V> get(K key) {
        try {
            byte[] keyState = codec.getMapKeyEncoder().encode(key);
            String keyHash = hash(keyState);
            String setName = getValuesName(keyHash);

            return new RedissonSetMultimapValues<V>(codec, commandExecutor, setName, getTimeoutSetName(), key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public RFuture<Collection<V>> getAllAsync(K key) {
        try {
            byte[] keyState = codec.getMapKeyEncoder().encode(key);
            String keyHash = hash(keyState);
            String setName = getValuesName(keyHash);
            
            return commandExecutor.evalReadAsync(getName(), codec, RedisCommands.EVAL_SET,
                    "local expireDate = 92233720368547758; " +
                    "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); "
                  + "if expireDateScore ~= false then "
                      + "expireDate = tonumber(expireDateScore) "
                  + "end; "
                  + "if expireDate > tonumber(ARGV[1]) then " +
                       "return redis.call('smembers', KEYS[1]); " +
                    "end; " +
                    "return {}; ",
                Arrays.<Object>asList(setName, getTimeoutSetName()), System.currentTimeMillis(), keyState);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public RFuture<Collection<V>> removeAllAsync(Object key) {
        try {
            byte[] keyState = codec.getMapKeyEncoder().encode(key);
            String keyHash = hash(keyState);

            String setName = getValuesName(keyHash);
            return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_SET,
                    "redis.call('hdel', KEYS[1], ARGV[1]); " +
                    "local members = redis.call('smembers', KEYS[2]); " +
                    "redis.call('del', KEYS[2]); " +
                    "redis.call('zrem', KEYS[3], ARGV[1]); " +
                    "return members; ",
                Arrays.<Object>asList(getName(), setName, getTimeoutSetName()), keyState);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean expireKey(K key, long timeToLive, TimeUnit timeUnit) {
        return get(expireKeyAsync(key, timeToLive, timeUnit));
    }
    
    @Override
    public RFuture<Boolean> expireKeyAsync(K key, long timeToLive, TimeUnit timeUnit) {
        return baseCache.expireKeyAsync(key, timeToLive, timeUnit);
    }
    
    @Override
    public RFuture<Boolean> deleteAsync() {
        return baseCache.deleteAsync();
    }

    @Override
    public RFuture<Boolean> expireAsync(long timeToLive, TimeUnit timeUnit) {
        return baseCache.expireAsync(timeToLive, timeUnit);
    }

    @Override
    public RFuture<Boolean> expireAtAsync(long timestamp) {
        return baseCache.expireAtAsync(timestamp);
    }

    @Override
    public RFuture<Boolean> clearExpireAsync() {
        return baseCache.clearExpireAsync();
    }
    
}
