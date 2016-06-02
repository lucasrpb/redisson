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
package org.redisson.connection;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.redisson.MasterSlaveServersConfig;
import org.redisson.ReadMode;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisConnection;
import org.redisson.client.RedisPubSubConnection;
import org.redisson.client.RedisPubSubListener;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.CommandData;
import org.redisson.cluster.ClusterSlotRange;
import org.redisson.connection.ClientConnectionsEntry.FreezeReason;
import org.redisson.connection.balancer.LoadBalancerManager;
import org.redisson.connection.balancer.LoadBalancerManagerImpl;
import org.redisson.connection.pool.MasterConnectionPool;
import org.redisson.core.NodeType;
import org.redisson.core.RFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

/**
 *
 * @author Nikita Koksharov
 *
 */
public class MasterSlaveEntry {

    final Logger log = LoggerFactory.getLogger(getClass());

    LoadBalancerManager slaveBalancer;
    ClientConnectionsEntry masterEntry;

    final MasterSlaveServersConfig config;
    final ConnectionManager connectionManager;

    final MasterConnectionPool writeConnectionHolder;
    final Set<ClusterSlotRange> slotRanges;

    final AtomicBoolean active = new AtomicBoolean(true);

    public MasterSlaveEntry(Set<ClusterSlotRange> slotRanges, ConnectionManager connectionManager, MasterSlaveServersConfig config) {
        this.slotRanges = slotRanges;
        this.connectionManager = connectionManager;
        this.config = config;

        slaveBalancer = new LoadBalancerManagerImpl(config, connectionManager, this);
        writeConnectionHolder = new MasterConnectionPool(config, connectionManager, this);
    }

    public List<RFuture<Void>> initSlaveBalancer(Collection<URI> disconnectedNodes) {
        boolean freezeMasterAsSlave = !config.getSlaveAddresses().isEmpty()
                    && config.getReadMode() == ReadMode.SLAVE
                        && disconnectedNodes.size() < config.getSlaveAddresses().size();

        List<RFuture<Void>> result = new LinkedList<RFuture<Void>>();
        RFuture<Void> f = addSlave(config.getMasterAddress().getHost(), config.getMasterAddress().getPort(), freezeMasterAsSlave, NodeType.MASTER);
        result.add(f);
        for (URI address : config.getSlaveAddresses()) {
            f = addSlave(address.getHost(), address.getPort(), disconnectedNodes.contains(address), NodeType.SLAVE);
            result.add(f);
        }
        return result;
    }

    public RFuture<Void> setupMasterEntry(String host, int port) {
        RedisClient client = connectionManager.createClient(NodeType.MASTER, host, port);
        masterEntry = new ClientConnectionsEntry(client, config.getMasterConnectionMinimumIdleSize(), config.getMasterConnectionPoolSize(),
                                                    0, 0, connectionManager, NodeType.MASTER);
        return writeConnectionHolder.add(masterEntry);
    }

    private boolean slaveDown(ClientConnectionsEntry entry, FreezeReason freezeReason) {
        ClientConnectionsEntry e = slaveBalancer.freeze(entry, freezeReason);
        if (e == null) {
            return false;
        }
        
        return slaveDown(e);
    }
    
    public boolean slaveDown(String host, int port, FreezeReason freezeReason) {
        ClientConnectionsEntry entry = slaveBalancer.freeze(host, port, freezeReason);
        if (entry == null) {
            return false;
        }
        
        return slaveDown(entry);
    }

    private boolean slaveDown(ClientConnectionsEntry entry) {
        // add master as slave if no more slaves available
        if (config.getReadMode() == ReadMode.SLAVE && slaveBalancer.getAvailableClients() == 0) {
            InetSocketAddress addr = masterEntry.getClient().getAddr();
            if (slaveUp(addr.getHostName(), addr.getPort(), FreezeReason.SYSTEM)) {
                log.info("master {}:{} used as slave", addr.getHostName(), addr.getPort());
            }
        }
        
        // close all connections
        while (true) {
            final RedisConnection connection = entry.pollConnection();
            if (connection == null) {
                break;
            }
           
            connection.closeAsync().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    reattachBlockingQueue(connection);
                }
            });
        }

        // close all pub/sub connections
        while (true) {
            RedisPubSubConnection connection = entry.pollSubscribeConnection();
            if (connection == null) {
                break;
            }
            connection.closeAsync();
        }
        
        for (RedisPubSubConnection connection : entry.getAllSubscribeConnections()) {
            reattachPubSub(connection);
        }
        entry.getAllSubscribeConnections().clear();
        
        return true;
    }
    
    private void reattachPubSub(RedisPubSubConnection redisPubSubConnection) {
        for (String channelName : redisPubSubConnection.getChannels().keySet()) {
            PubSubConnectionEntry pubSubEntry = connectionManager.getPubSubEntry(channelName);

            synchronized (pubSubEntry) {
                pubSubEntry.close();

                Collection<RedisPubSubListener> listeners = pubSubEntry.getListeners(channelName);
                reattachPubSubListeners(channelName, listeners);
            }
        }

        for (String channelName : redisPubSubConnection.getPatternChannels().keySet()) {
            PubSubConnectionEntry pubSubEntry = connectionManager.getPubSubEntry(channelName);

            synchronized (pubSubEntry) {
                pubSubEntry.close();

                Collection<RedisPubSubListener> listeners = pubSubEntry.getListeners(channelName);
                reattachPatternPubSubListeners(channelName, listeners);
            }
        }
    }

    private void reattachPubSubListeners(String channelName, Collection<RedisPubSubListener> listeners) {
        Codec subscribeCodec = connectionManager.unsubscribe(channelName);
        if (!listeners.isEmpty()) {
            RFuture<PubSubConnectionEntry> future = connectionManager.subscribe(subscribeCodec, channelName, null);
            future.thenAccept(newEntry -> {
                for (RedisPubSubListener redisPubSubListener : listeners) {
                    newEntry.addListener(channelName, redisPubSubListener);
                }
                log.debug("listeners resubscribed to '{}' channel", channelName);
            }).exceptionally(cause -> {
                log.error("Can't resubscribe listeners to topic channel: " + channelName, cause);
                return null;
            });
        }
    }

    private void reattachPatternPubSubListeners(final String channelName,
            final Collection<RedisPubSubListener> listeners) {
        Codec subscribeCodec = connectionManager.punsubscribe(channelName);
        if (!listeners.isEmpty()) {
            RFuture<PubSubConnectionEntry> future = connectionManager.psubscribe(channelName, subscribeCodec);
            future.thenAccept(newEntry -> {
                for (RedisPubSubListener redisPubSubListener : listeners) {
                    newEntry.addListener(channelName, redisPubSubListener);
                }
                log.debug("listeners resubscribed to '{}' channel-pattern", channelName);
            }).exceptionally(cause -> {
                log.error("Can't resubscribe listeners to '" + channelName + "' channel-pattern", cause);
                return null;
            });
        }
    }
    
    private void reattachBlockingQueue(RedisConnection connection) {
        final CommandData<?, ?> commandData = connection.getCurrentCommand();

        if (commandData == null 
                || !commandData.isBlockingCommand()) {
            return;
        }

        RFuture<RedisConnection> future = connectionReadOp();
        future.thenAccept(newConnection -> {
            FutureListener<Object> listener = new FutureListener<Object>() {
                @Override
                public void operationComplete(Future<Object> future) throws Exception {
                    releaseRead(newConnection);
                }
            };
            commandData.getPromise().addListener(listener);
            if (commandData.getPromise().isDone()) {
                return;
            }
            ChannelFuture channelFuture = newConnection.send(commandData);
            channelFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        listener.operationComplete(null);
                        commandData.getPromise().removeListener(listener);
                        releaseRead(newConnection);
                        log.error("Can't resubscribe blocking queue {}", commandData);
                    }
                }
            });
        }).exceptionally(cause -> {
            log.error("Can't listeners resubscribe to blocking queue " + commandData, cause);
            return null;
        });
    }

    public RFuture<Void> addSlave(String host, int port) {
        return addSlave(host, port, true, NodeType.SLAVE);
    }

    private RFuture<Void> addSlave(String host, int port, boolean freezed, NodeType mode) {
        RedisClient client = connectionManager.createClient(NodeType.SLAVE, host, port);
        ClientConnectionsEntry entry = new ClientConnectionsEntry(client,
                this.config.getSlaveConnectionMinimumIdleSize(),
                this.config.getSlaveConnectionPoolSize(),
                this.config.getSlaveSubscriptionConnectionMinimumIdleSize(),
                this.config.getSlaveSubscriptionConnectionPoolSize(), connectionManager, mode);
        if (freezed) {
            entry.setFreezed(freezed);
            entry.setFreezeReason(FreezeReason.SYSTEM);
        }
        return slaveBalancer.add(entry);
    }

    public RedisClient getClient() {
        return masterEntry.getClient();
    }

    public boolean slaveUp(String host, int port, FreezeReason freezeReason) {
        if (!slaveBalancer.unfreeze(host, port, freezeReason)) {
            return false;
        }

        InetSocketAddress addr = masterEntry.getClient().getAddr();
        // exclude master from slaves
        if (config.getReadMode() == ReadMode.SLAVE
                && (!addr.getHostName().equals(host) || port != addr.getPort())) {
            slaveDown(addr.getHostName(), addr.getPort(), FreezeReason.SYSTEM);
            log.info("master {}:{} excluded from slaves", addr.getHostName(), addr.getPort());
        }
        return true;
    }

    /**
     * Freeze slave with <code>host:port</code> from slaves list.
     * Re-attach pub/sub listeners from it to other slave.
     * Shutdown old master client.
     *
     */
    public void changeMaster(String host, int port) {
        ClientConnectionsEntry oldMaster = masterEntry;
        setupMasterEntry(host, port);
        writeConnectionHolder.remove(oldMaster);
        slaveDown(oldMaster, FreezeReason.MANAGER);

        // more than one slave available, so master can be removed from slaves
        if (config.getReadMode() == ReadMode.SLAVE
                && slaveBalancer.getAvailableClients() > 1) {
            slaveDown(host, port, FreezeReason.SYSTEM);
        }
        connectionManager.shutdownAsync(oldMaster.getClient());
    }

    public boolean isFreezed() {
        return masterEntry.isFreezed();
    }

    public FreezeReason getFreezeReason() {
        return masterEntry.getFreezeReason();
    }

    public void freeze() {
        masterEntry.freezeMaster(FreezeReason.MANAGER);
    }

    public void unfreeze() {
        masterEntry.resetFailedAttempts();
        masterEntry.setFreezed(false);
        masterEntry.setFreezeReason(null);
    }

    public void shutdownMasterAsync() {
        if (!active.compareAndSet(true, false)) {
            return;
        }

        connectionManager.shutdownAsync(masterEntry.getClient());
        slaveBalancer.shutdownAsync();
    }

    public RFuture<RedisConnection> connectionWriteOp() {
        return writeConnectionHolder.get();
    }

    public RFuture<RedisConnection> connectionReadOp() {
        return slaveBalancer.nextConnection();
    }

    public RFuture<RedisConnection> connectionReadOp(InetSocketAddress addr) {
        return slaveBalancer.getConnection(addr);
    }


    RFuture<RedisPubSubConnection> nextPubSubConnection() {
        return slaveBalancer.nextPubSubConnection();
    }

    public void returnPubSubConnection(PubSubConnectionEntry entry) {
        slaveBalancer.returnPubSubConnection(entry.getConnection());
    }

    public void releaseWrite(RedisConnection connection) {
        writeConnectionHolder.returnConnection(masterEntry, connection);
    }

    public void releaseRead(RedisConnection сonnection) {
        slaveBalancer.returnConnection(сonnection);
    }

    public void shutdown() {
        if (!active.compareAndSet(true, false)) {
            return;
        }

        masterEntry.getClient().shutdown();
        slaveBalancer.shutdown();
    }

    public void addSlotRange(ClusterSlotRange range) {
        slotRanges.add(range);
    }

    public void removeSlotRange(ClusterSlotRange range) {
        slotRanges.remove(range);
    }

    public Set<ClusterSlotRange> getSlotRanges() {
        return slotRanges;
    }

}
