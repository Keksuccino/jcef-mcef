// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.browser.CefDevToolsClient;
import org.cef.browser.CefRegistration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

class CefDevToolsClientTest {
    private static final Constructor<CefDevToolsClient> TEST_CONSTRUCTOR = getTestConstructor();
    private static final Method METHOD_RESULT_CALLBACK = getObserverMethod("onDevToolsMethodResult",
            org.cef.browser.CefBrowser.class, int.class, boolean.class, String.class);
    private static final Method EVENT_CALLBACK = getObserverMethod(
            "onDevToolsEvent", org.cef.browser.CefBrowser.class, String.class, String.class);
    private static final Method AGENT_ATTACHED_CALLBACK =
            getObserverMethod("onDevToolsAgentAttached", org.cef.browser.CefBrowser.class);
    private static final Method AGENT_DETACHED_CALLBACK =
            getObserverMethod("onDevToolsAgentDetached", org.cef.browser.CefBrowser.class);
    private static final Field EARLY_RESULTS_FIELD = getField("earlyResults_");

    @Test
    void boundsAndClearsResultsThatCannotBeCorrelatedToThisClient() {
        CompletableFuture<Integer> messageId = new CompletableFuture<Integer>();
        TestHarness harness = new TestHarness((method, parameters) -> messageId);
        CompletableFuture<String> command = harness.client_.executeDevToolsMethod("Runtime.enable");

        for (int id = 1; id <= 100; id++) {
            harness.reportResult(id, true, "{}");
        }
        assertTrue(bufferedEarlyResultCount(harness.client_) <= 64);

        messageId.complete(Integer.valueOf(1000));
        assertEquals(0, bufferedEarlyResultCount(harness.client_));
        assertFalse(command.isDone());

        harness.reportResult(2000, true, "external");
        assertEquals(0, bufferedEarlyResultCount(harness.client_));
        harness.reportResult(1000, true, "result");
        assertEquals("result", command.join());

        harness.client_.close();
        harness.client_.close();
        assertEquals(1, harness.registration_.disposeCount_.get());
    }

    @Test
    void correlatesResultDeliveredBeforeMessageIdAssignment() {
        CompletableFuture<Integer> messageId = new CompletableFuture<Integer>();
        TestHarness harness = new TestHarness((method, parameters) -> messageId);
        CompletableFuture<String> command = harness.client_.executeDevToolsMethod("Page.enable");

        harness.reportResult(42, true, "early");
        assertEquals(1, bufferedEarlyResultCount(harness.client_));
        messageId.complete(Integer.valueOf(42));

        assertEquals("early", command.join());
        assertEquals(0, bufferedEarlyResultCount(harness.client_));
        harness.client_.close();
    }

    @Test
    void preservesFirstCommandWhenASecondCommandReturnsDuplicateId() {
        AtomicInteger invocation = new AtomicInteger();
        CompletableFuture<Integer> firstId = new CompletableFuture<Integer>();
        CompletableFuture<Integer> secondId = new CompletableFuture<Integer>();
        TestHarness harness = new TestHarness(
                (method, parameters) -> invocation.getAndIncrement() == 0 ? firstId : secondId);
        CompletableFuture<String> first = harness.client_.executeDevToolsMethod("Runtime.enable");
        CompletableFuture<String> second = harness.client_.executeDevToolsMethod("Page.enable");

        firstId.complete(Integer.valueOf(7));
        secondId.complete(Integer.valueOf(7));
        assertThrows(CompletionException.class, second::join);
        assertFalse(first.isDone());

        harness.reportResult(7, true, "first");
        assertEquals("first", first.join());
        harness.client_.close();
    }

    @Test
    void supportsListenerMutationDuringEventDeliveryAndClosesPendingCommands() {
        CompletableFuture<Integer> messageId = new CompletableFuture<Integer>();
        TestHarness harness = new TestHarness((method, parameters) -> messageId);
        AtomicInteger firstListenerCalls = new AtomicInteger();
        AtomicInteger secondListenerCalls = new AtomicInteger();
        AtomicReference<CefDevToolsClient.EventListener> firstListener =
                new AtomicReference<CefDevToolsClient.EventListener>();
        firstListener.set((event, parameters) -> {
            firstListenerCalls.incrementAndGet();
            harness.client_.removeEventListener(firstListener.get());
        });
        harness.client_.addEventListener(firstListener.get());
        harness.client_.addEventListener(
                (event, parameters) -> secondListenerCalls.incrementAndGet());

        harness.reportEvent("Page.loadEventFired", "{}");
        harness.reportEvent("Page.loadEventFired", "{}");
        assertEquals(1, firstListenerCalls.get());
        assertEquals(2, secondListenerCalls.get());

        CompletableFuture<String> command = harness.client_.executeDevToolsMethod("Runtime.enable");
        harness.client_.close();
        assertThrows(CompletionException.class, command::join);
        messageId.complete(Integer.valueOf(9));
        harness.reportResult(9, true, "late");
        assertEquals(0, bufferedEarlyResultCount(harness.client_));
    }

    @Test
    void agentDetachFailsPendingCommandsAndPreservesJavaListenersForReattach() {
        AtomicInteger invocation = new AtomicInteger();
        CompletableFuture<Integer> detachedMessageId = new CompletableFuture<Integer>();
        CompletableFuture<Integer> reattachedMessageId = new CompletableFuture<Integer>();
        TestHarness harness =
                new TestHarness((method, parameters)
                                        -> invocation.getAndIncrement() == 0 ? detachedMessageId
                                                                             : reattachedMessageId);
        AtomicInteger eventCalls = new AtomicInteger();
        harness.client_.addEventListener((event, parameters) -> eventCalls.incrementAndGet());
        CompletableFuture<String> detachedCommand =
                harness.client_.executeDevToolsMethod("Network.enable");

        harness.reportAgentDetached();
        assertThrows(CompletionException.class, detachedCommand::join);
        assertFalse(harness.client_.isClosed());

        CompletableFuture<String> reattachedCommand =
                harness.client_.executeDevToolsMethod("Network.enable");
        detachedMessageId.complete(Integer.valueOf(17));
        harness.reportResult(17, true, "late");
        harness.reportAgentAttached();
        harness.reportResult(18, true, "reattached");
        reattachedMessageId.complete(Integer.valueOf(18));
        assertEquals("reattached", reattachedCommand.join());
        harness.reportEvent("Network.requestWillBeSent", "{}");
        assertEquals(1, eventCalls.get());
        assertEquals(0, bufferedEarlyResultCount(harness.client_));
        harness.client_.close();
    }

    private static final class TestHarness {
        private final TestRegistration registration_ = new TestRegistration();
        private final AtomicReference<Object> observer_ = new AtomicReference<Object>();
        private final CefDevToolsClient client_;

        private TestHarness(BiFunction<String, String, CompletableFuture<Integer>> methodExecutor) {
            Function<Object, CefRegistration> registrar = observer -> {
                observer_.set(observer);
                return registration_;
            };
            client_ = newClient(methodExecutor, registrar);
        }

        private void reportResult(int messageId, boolean success, String result) {
            invokeObserver(METHOD_RESULT_CALLBACK, observer_.get(), null,
                    Integer.valueOf(messageId), Boolean.valueOf(success), result);
        }

        private void reportEvent(String event, String parameters) {
            invokeObserver(EVENT_CALLBACK, observer_.get(), null, event, parameters);
        }

        private void reportAgentDetached() {
            invokeObserver(AGENT_DETACHED_CALLBACK, observer_.get(), new Object[] {null});
        }

        private void reportAgentAttached() {
            invokeObserver(AGENT_ATTACHED_CALLBACK, observer_.get(), new Object[] {null});
        }
    }

    private static final class TestRegistration extends CefRegistration {
        private final AtomicInteger disposeCount_ = new AtomicInteger();

        @Override
        public void dispose() {
            disposeCount_.compareAndSet(0, 1);
        }
    }

    private static CefDevToolsClient newClient(
            BiFunction<String, String, CompletableFuture<Integer>> methodExecutor,
            Function<Object, CefRegistration> registrar) {
        try {
            return TEST_CONSTRUCTOR.newInstance(methodExecutor, registrar);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static int bufferedEarlyResultCount(CefDevToolsClient client) {
        try {
            return ((Map<?, ?>) EARLY_RESULTS_FIELD.get(client)).size();
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Constructor<CefDevToolsClient> getTestConstructor() {
        try {
            Constructor<CefDevToolsClient> constructor =
                    CefDevToolsClient.class.getDeclaredConstructor(
                            BiFunction.class, Function.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Method getObserverMethod(String name, Class<?>... parameterTypes) {
        try {
            Class<?> observerClass = Class.forName("org.cef.browser.CefDevToolsMessageObserver");
            Method method = observerClass.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Field getField(String name) {
        try {
            Field field = CefDevToolsClient.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void invokeObserver(Method method, Object observer, Object... arguments) {
        try {
            method.invoke(observer, arguments);
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) throw(RuntimeException) cause;
            if (cause instanceof Error) throw(Error) cause;
            throw new AssertionError(cause);
        }
    }
}
