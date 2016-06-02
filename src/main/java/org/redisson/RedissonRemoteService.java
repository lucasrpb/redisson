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
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.redisson.client.codec.LongCodec;
import org.redisson.core.RBatch;
import org.redisson.core.RBlockingQueue;
import org.redisson.core.RBlockingQueueAsync;
import org.redisson.core.RFuture;
import org.redisson.core.RRemoteService;
import org.redisson.core.RScript;
import org.redisson.core.RScript.Mode;
import org.redisson.core.RemoteInvocationOptions;
import org.redisson.remote.RRemoteAsync;
import org.redisson.remote.RRemoteServiceResponse;
import org.redisson.remote.RemoteServiceAck;
import org.redisson.remote.RemoteServiceAckTimeoutException;
import org.redisson.remote.RemoteServiceKey;
import org.redisson.remote.RemoteServiceMethod;
import org.redisson.remote.RemoteServiceRequest;
import org.redisson.remote.RemoteServiceResponse;
import org.redisson.remote.RemoteServiceTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBufUtil;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonRemoteService implements RRemoteService {

    private static final Logger log = LoggerFactory.getLogger(RedissonRemoteService.class); 
    
    private final Map<RemoteServiceKey, RemoteServiceMethod> beans = PlatformDependent.newConcurrentHashMap();
    
    private final Redisson redisson;
    private final String name;
    
    public RedissonRemoteService(Redisson redisson) {
        this(redisson, "redisson_remote_service");
    }

    public RedissonRemoteService(Redisson redisson, String name) {
        this.redisson = redisson;
        this.name = name;
    }

    @Override
    public <T> void register(Class<T> remoteInterface, T object) {
        register(remoteInterface, object, 1);
    }
    
    @Override
    public <T> void register(Class<T> remoteInterface, T object, int executorsAmount) {
        if (executorsAmount < 1) {
            throw new IllegalArgumentException("executorsAmount can't be lower than 1");
        }
        for (Method method : remoteInterface.getMethods()) {
            RemoteServiceMethod value = new RemoteServiceMethod(method, object);
            RemoteServiceKey key = new RemoteServiceKey(remoteInterface, method.getName());
            if (beans.put(key, value) != null) {
                return;
            }
        }
        
        for (int i = 0; i < executorsAmount; i++) {
            String requestQueueName = name + ":{" + remoteInterface.getName() + "}";
            RBlockingQueue<RemoteServiceRequest> requestQueue = redisson.getBlockingQueue(requestQueueName);
            subscribe(remoteInterface, requestQueue);
        }
    }

    private byte[] encode(Object obj) {
        try {
            return redisson.getConfig().getCodec().getValueEncoder().encode(obj);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
    
    private <T> void subscribe(final Class<T> remoteInterface, final RBlockingQueue<RemoteServiceRequest> requestQueue) {
        RFuture<RemoteServiceRequest> take = requestQueue.takeAsync();
        take.thenAccept(request -> {
            // do not subscribe now, see https://github.com/mrniko/redisson/issues/493
            // subscribe(remoteInterface, requestQueue);

            // check the ack only if expected
            if (request.getOptions().isAckExpected() && System.currentTimeMillis() - request.getDate() > request.getOptions().getAckTimeoutInMillis()) {
                log.debug("request: {} has been skipped due to ackTimeout");
                // re-subscribe after a skipped ackTimeout
                subscribe(remoteInterface, requestQueue);
                return;
            }
            
            final RemoteServiceMethod method = beans.get(new RemoteServiceKey(remoteInterface, request.getMethodName()));
            final String responseName = name + ":{" + remoteInterface.getName() + "}:" + request.getRequestId();

            // send the ack only if expected
            if (request.getOptions().isAckExpected()) {
                String ackName = name + ":{" + remoteInterface.getName() + "}:ack";
                RFuture<Boolean> ackClientsFuture = redisson.getScript().evalAsync(responseName, Mode.READ_WRITE, LongCodec.INSTANCE, 
                        "if redis.call('setnx', KEYS[1], 1) == 1 then "
                        + "redis.call('pexpire', KEYS[1], ARGV[2]);"
                        + "redis.call('rpush', KEYS[2], ARGV[1]);"
                        + "redis.call('pexpire', KEYS[2], ARGV[2]);"
                        + "return 1;"
                       + "end;"
                       + "return 0;", RScript.ReturnType.BOOLEAN, Arrays.<Object>asList(ackName, responseName), 
                           encode(new RemoteServiceAck()), request.getOptions().getAckTimeoutInMillis());
                
                ackClientsFuture.thenAccept(r -> {
                    if (!r) {
                        subscribe(remoteInterface, requestQueue);
                        return;
                    }
                    
                    invokeMethod(remoteInterface, requestQueue, request, method, responseName);
                }).exceptionally(cause -> {
                    log.error("Can't send ack for request: " + request, cause);
                    if (cause instanceof RedissonShutdownException) {
                        return null;
                    }
                    // re-subscribe after a failed send (ack)
                    subscribe(remoteInterface, requestQueue);
                    return null;
                });
            } else {
                invokeMethod(remoteInterface, requestQueue, request, method, responseName);
            }
        }).exceptionally(cause -> {
            if (cause instanceof RedissonShutdownException) {
                return null;
            }
            // re-subscribe after a failed takeAsync
            subscribe(remoteInterface, requestQueue);
            return null;
        });
    }

    private <T> void invokeMethod(final Class<T> remoteInterface, final RBlockingQueue<RemoteServiceRequest> requestQueue, final RemoteServiceRequest request, RemoteServiceMethod method, String responseName) {
        final AtomicReference<RemoteServiceResponse> responseHolder = new AtomicReference<RemoteServiceResponse>();
        try {
            Object result = method.getMethod().invoke(method.getBean(), request.getArgs());
            RemoteServiceResponse response = new RemoteServiceResponse(result);
            responseHolder.set(response);
        } catch (Exception e) {
            RemoteServiceResponse response = new RemoteServiceResponse(e.getCause());
            responseHolder.set(response);
            log.error("Can't execute: " + request, e);
        }

        // send the response only if expected
        if (request.getOptions().isResultExpected()) {
            RFuture<List<?>> clientsFuture = send(request.getOptions().getExecutionTimeoutInMillis(), responseName, responseHolder.get());
            clientsFuture.handle((r, cause) -> {
                if (cause != null) {
                    log.error("Can't send response: " + responseHolder.get() + " for request: " + request, cause);
                    if (cause instanceof RedissonShutdownException) {
                        return null;
                    }
                }
                // re-subscribe anyways (fail or success) after the send (response)
                subscribe(remoteInterface, requestQueue);
                return null;
            });
        } else {
            // re-subscribe anyways after the method invocation
            subscribe(remoteInterface, requestQueue);
        }
    }

    @Override
    public <T> T get(Class<T> remoteInterface) {
        return get(remoteInterface, RemoteInvocationOptions.defaults());
    }

    @Override
    public <T> T get(Class<T> remoteInterface, long executionTimeout, TimeUnit executionTimeUnit) {
        return get(remoteInterface, RemoteInvocationOptions.defaults()
                .expectResultWithin(executionTimeout, executionTimeUnit));
    }

    public <T> T get(Class<T> remoteInterface, long executionTimeout, TimeUnit executionTimeUnit,
                     long ackTimeout, TimeUnit ackTimeUnit) {
        return get(remoteInterface, RemoteInvocationOptions.defaults()
                .expectAckWithin(ackTimeout, ackTimeUnit)
                .expectResultWithin(executionTimeout, executionTimeUnit));
    }

    public <T> T get(Class<T> remoteInterface, RemoteInvocationOptions options) {
        for (Annotation annotation : remoteInterface.getAnnotations()) {
            if (annotation.annotationType() == RRemoteAsync.class) {
                Class<T> syncInterface = (Class<T>) ((RRemoteAsync)annotation).value();
                for (Method m : remoteInterface.getMethods()) {
                    try {
                        syncInterface.getMethod(m.getName(), m.getParameterTypes());
                    } catch (NoSuchMethodException e) {
                        throw new IllegalArgumentException("Method '" + m.getName() + "' with params '" + Arrays.toString(m.getParameterTypes()) 
                                        + "' isn't defined in " + syncInterface);
                    } catch (SecurityException e) {
                        throw new IllegalArgumentException(e);
                    }
                    if (!m.getReturnType().getClass().isInstance(RFuture.class)) {
                        throw new IllegalArgumentException(m.getReturnType().getClass() + " isn't allowed as return type");
                    }
                }
                return async(remoteInterface, options, syncInterface.getName());
            }
        }
        
        return sync(remoteInterface, options);
    }
    
    private <T> T async(Class<T> remoteInterface, final RemoteInvocationOptions options, final String interfaceName) {
        // local copy of the options, to prevent mutation
        final RemoteInvocationOptions optionsCopy = new RemoteInvocationOptions(options);
        final String toString = getClass().getSimpleName() + "-" + remoteInterface.getSimpleName() + "-proxy-" + generateRequestId();
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getName().equals("toString")) {
                    return toString;
                } else if (method.getName().equals("equals")) {
                    return proxy == args[0];
                } else if (method.getName().equals("hashCode")) {
                    return toString.hashCode();
                }

                if (!optionsCopy.isResultExpected() && !(method.getReturnType().equals(Void.class) || method.getReturnType().equals(Void.TYPE)))
                    throw new IllegalArgumentException("The noResult option only supports void return value");

                final String requestId = generateRequestId();
                final Promise<Object> result = ImmediateEventExecutor.INSTANCE.newPromise();

                String requestQueueName = name + ":{" + interfaceName + "}";
                RBlockingQueue<RemoteServiceRequest> requestQueue = redisson.getBlockingQueue(requestQueueName);
                final RemoteServiceRequest request = new RemoteServiceRequest(requestId,
                        method.getName(), args, optionsCopy, System.currentTimeMillis());
                RFuture<Boolean> addFuture = requestQueue.addAsync(request);
                addFuture.thenAccept(r -> {
                    final RBlockingQueue<? extends RRemoteServiceResponse> responseQueue;
                    if (optionsCopy.isAckExpected() || optionsCopy.isResultExpected()) {
                        String responseName = name + ":{" + interfaceName + "}:" + requestId;
                        responseQueue = redisson.getBlockingQueue(responseName);
                    } else {
                        responseQueue = null;
                    }
                    
                    // poll for the ack only if expected
                    if (optionsCopy.isAckExpected()) {
                        final String ackName = name + ":{" + interfaceName + "}:ack";
                        RFuture<RemoteServiceAck> ackFuture = (RFuture<RemoteServiceAck>) responseQueue.pollAsync(optionsCopy.getAckTimeoutInMillis(), TimeUnit.MILLISECONDS);
                        ackFuture.thenAccept(ack -> {
                            if (ack == null) {
                                RFuture<RemoteServiceAck> ackFutureAttempt = tryPollAckAgainAsync(optionsCopy, responseQueue, ackName);
                                ackFutureAttempt.thenAccept(res -> {
                                    if (res == null) {
                                        Exception ex = new RemoteServiceAckTimeoutException("No ACK response after " + optionsCopy.getAckTimeoutInMillis() + "ms for request: " + request);
                                        result.setFailure(ex);
                                        return;
                                    }
                                    
                                    invokeAsync(optionsCopy, result, request, responseQueue, ackName);
                                }).exceptionally(cause -> {
                                    result.setFailure(cause);
                                    return null;
                                });
                            } else {
                                invokeAsync(optionsCopy, result, request, responseQueue, ackName);
                            }
                        });
                    }
                }).exceptionally(cause -> {
                    result.setFailure(cause);
                    return null;
                });

                return result;
            }

        };
        return (T) Proxy.newProxyInstance(remoteInterface.getClassLoader(), new Class[]{remoteInterface}, handler);
    }

    private void invokeAsync(final RemoteInvocationOptions optionsCopy,
            final Promise<Object> result, final RemoteServiceRequest request,
            final RBlockingQueue<? extends RRemoteServiceResponse> responseQueue,
            final String ackName) {
        RFuture<Boolean> deleteFuture = redisson.getBucket(ackName).deleteAsync();
        deleteFuture.thenAccept(r -> {
            // poll for the response only if expected
            if (optionsCopy.isResultExpected()) {
                RFuture<RemoteServiceResponse> responseFuture = (RFuture<RemoteServiceResponse>) responseQueue.pollAsync(optionsCopy.getExecutionTimeoutInMillis(), TimeUnit.MILLISECONDS);
                responseFuture.thenAccept(resp -> {
                    if (resp == null) {
                        RemoteServiceTimeoutException e = new RemoteServiceTimeoutException("No response after " + optionsCopy.getExecutionTimeoutInMillis() + "ms for request: " + request);
                        result.setFailure(e);
                        return;
                    }
                    
                    if (resp.getError() != null) {
                        result.setFailure(resp.getError());
                    }
                    
                    result.setSuccess(resp.getResult());
                }).exceptionally(cause -> {
                    result.setFailure(cause);
                    return null;
                });
            }
        }).exceptionally(cause -> {
            result.setFailure(cause);
            return null;
        });
    }

    private <T> T sync(Class<T> remoteInterface, final RemoteInvocationOptions options) {
        final String interfaceName = remoteInterface.getName();
        // local copy of the options, to prevent mutation
        final RemoteInvocationOptions optionsCopy = new RemoteInvocationOptions(options);
        final String toString = getClass().getSimpleName() + "-" + remoteInterface.getSimpleName() + "-proxy-" + generateRequestId();
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getName().equals("toString")) {
                    return toString;
                } else if (method.getName().equals("equals")) {
                    return proxy == args[0];
                } else if (method.getName().equals("hashCode")) {
                    return toString.hashCode();
                }

                if (!optionsCopy.isResultExpected() && !(method.getReturnType().equals(Void.class) || method.getReturnType().equals(Void.TYPE)))
                    throw new IllegalArgumentException("The noResult option only supports void return value");

                String requestId = generateRequestId();

                String requestQueueName = name + ":{" + interfaceName + "}";
                RBlockingQueue<RemoteServiceRequest> requestQueue = redisson.getBlockingQueue(requestQueueName);
                RemoteServiceRequest request = new RemoteServiceRequest(requestId,
                        method.getName(), args, optionsCopy, System.currentTimeMillis());
                requestQueue.add(request);

                RBlockingQueue<RRemoteServiceResponse> responseQueue = null;
                if (optionsCopy.isAckExpected() || optionsCopy.isResultExpected()) {
                    String responseName = name + ":{" + interfaceName + "}:" + requestId;
                    responseQueue = redisson.getBlockingQueue(responseName);
                }

                // poll for the ack only if expected
                if (optionsCopy.isAckExpected()) {
                    String ackName = name + ":{" + interfaceName + "}:ack";
                    RemoteServiceAck ack = (RemoteServiceAck) responseQueue.poll(optionsCopy.getAckTimeoutInMillis(), TimeUnit.MILLISECONDS);
                    if (ack == null) {
                        ack = tryPollAckAgain(optionsCopy, responseQueue, ackName);
                        if (ack == null) {
                            throw new RemoteServiceAckTimeoutException("No ACK response after " + optionsCopy.getAckTimeoutInMillis() + "ms for request: " + request);
                        }
                    }
                    redisson.getBucket(ackName).delete();
                }

                // poll for the response only if expected
                if (optionsCopy.isResultExpected()) {
                    RemoteServiceResponse response = (RemoteServiceResponse) responseQueue.poll(optionsCopy.getExecutionTimeoutInMillis(), TimeUnit.MILLISECONDS);
                    if (response == null) {
                        throw new RemoteServiceTimeoutException("No response after " + optionsCopy.getExecutionTimeoutInMillis() + "ms for request: " + request);
                    }
                    if (response.getError() != null) {
                        throw response.getError();
                    }
                    return response.getResult();
                }

                return null;
            }

        };
        return (T) Proxy.newProxyInstance(remoteInterface.getClassLoader(), new Class[]{remoteInterface}, handler);
    }

    private RemoteServiceAck tryPollAckAgain(RemoteInvocationOptions optionsCopy,
                                    RBlockingQueue<? extends RRemoteServiceResponse> responseQueue, String ackName) {
        RFuture<Boolean> ackClientsFuture = redisson.getScript().evalAsync(ackName, Mode.READ_WRITE, LongCodec.INSTANCE, 
                  "if redis.call('setnx', KEYS[1], 1) == 1 then "
                    + "redis.call('pexpire', KEYS[1], ARGV[1]);"
                    + "return 0;"
                + "end;"
                + "redis.call('del', KEYS[1]);"
                + "return 1;", RScript.ReturnType.BOOLEAN, Arrays.<Object>asList(ackName), optionsCopy.getAckTimeoutInMillis());
        
        if (ackClientsFuture.join()) {
            return (RemoteServiceAck) responseQueue.poll();
        }
        return null;
    }
    
    private RFuture<RemoteServiceAck> tryPollAckAgainAsync(RemoteInvocationOptions optionsCopy,
            final RBlockingQueue<? extends RRemoteServiceResponse> responseQueue, String ackName) {
        RedissonFuture<RemoteServiceAck> promise = new RedissonFuture<>();
        RFuture<Boolean> ackClientsFuture = redisson.getScript().evalAsync(ackName, Mode.READ_WRITE, LongCodec.INSTANCE,
                "if redis.call('setnx', KEYS[1], 1) == 1 then " + "redis.call('pexpire', KEYS[1], ARGV[1]);"
                        + "return 0;" + "end;" + "redis.call('del', KEYS[1]);" + "return 1;",
                RScript.ReturnType.BOOLEAN, Arrays.<Object> asList(ackName), optionsCopy.getAckTimeoutInMillis());
        ackClientsFuture.thenAccept(r -> {
            if (r) {
                RFuture<RemoteServiceAck> pollFuture = (RFuture<RemoteServiceAck>) responseQueue.pollAsync();
                pollFuture.thenAccept(res -> promise.setSuccess(res))
                .exceptionally(cause -> {
                    promise.setFailure(cause);
                    return null;
                });
            } else {
                promise.setSuccess(null);
            }
        }).exceptionally(cause -> {
            promise.setFailure(cause);
            return null;
        });
        
        return promise;
    }

    private String generateRequestId() {
        byte[] id = new byte[16];
        ThreadLocalRandom.current().nextBytes(id);
        return ByteBufUtil.hexDump(id);
    }

    private <T extends RRemoteServiceResponse> RFuture<List<?>> send(long timeout, String responseName, T response) {
        RBatch batch = redisson.createBatch();
        RBlockingQueueAsync<T> queue = batch.getBlockingQueue(responseName);
        queue.putAsync(response);
        queue.expireAsync(timeout, TimeUnit.MILLISECONDS);
        return batch.executeAsync();
    }
}
