// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiFunction;
import java.util.function.Function;

public class CefDevToolsClient implements AutoCloseable {
    private static final int MAX_BUFFERED_EARLY_RESULTS = 64;
    private final Object lifecycleLock_ = new Object();
    private final Map<Integer, CompletableFuture<String>> queuedCommands_ =
            new HashMap<Integer, CompletableFuture<String>>();
    private final Map<Integer, MethodResult> earlyResults_ =
            new LinkedHashMap<Integer, MethodResult>();
    private final Set<CompletableFuture<String>> pendingCommands_ =
            new HashSet<CompletableFuture<String>>();
    private final Set<EventListener> eventListeners_ = new CopyOnWriteArraySet<EventListener>();
    private final BiFunction<String, String, CompletableFuture<Integer>> methodExecutor_;
    private CefRegistration registration_;
    private int messageIdAssignmentsInFlight_;

    private static final class MethodResult {
        private final boolean success_;
        private final String result_;

        private MethodResult(boolean success, String result) {
            success_ = success;
            result_ = result;
        }
    }

    /**
     * Use {@link CefBrowser#getDevToolsClient()} to get an instance of this class.
     */
    CefDevToolsClient(CefBrowser_N browser) {
        this(browser::executeDevToolsMethod, browser::addDevToolsMessageObserver);
    }

    CefDevToolsClient(BiFunction<String, String, CompletableFuture<Integer>> methodExecutor,
            Function<CefDevToolsMessageObserver, CefRegistration> observerRegistrar) {
        methodExecutor_ = methodExecutor;

        registration_ = observerRegistrar.apply(new CefDevToolsMessageObserver() {
            @Override
            public void onDevToolsMethodResult(
                    CefBrowser browser, int messageId, boolean success, String result) {
                CompletableFuture<String> future;
                synchronized (lifecycleLock_) {
                    if (registration_ == null) return;
                    future = queuedCommands_.remove(messageId);
                    if (future == null) {
                        // Only calls made through this client can be correlated after an ID
                        // assignment completes. The observer also sees calls made directly through
                        // the host, so never retain unmatched results when no assignment is
                        // pending.
                        if (messageIdAssignmentsInFlight_ > 0) {
                            earlyResults_.put(messageId, new MethodResult(success, result));
                            trimEarlyResultsLocked();
                        }
                        return;
                    }
                }
                completeCommand(future, success, result);
            }

            @Override
            public void onDevToolsEvent(CefBrowser browser, String method, String parameters) {
                if (isClosed()) return;
                for (EventListener eventListener : eventListeners_) {
                    eventListener.onEvent(method, parameters);
                }
            }

            @Override
            public void onDevToolsAgentDetached(CefBrowser browser) {
                handleAgentDetached();
            }
        });
    }

    @Override
    public void close() {
        CefRegistration registration;
        Set<CompletableFuture<String>> pendingCommands;
        synchronized (lifecycleLock_) {
            registration = registration_;
            if (registration == null && pendingCommands_.isEmpty()) {
                eventListeners_.clear();
                return;
            }
            registration_ = null;
            pendingCommands = new LinkedHashSet<CompletableFuture<String>>(pendingCommands_);
            pendingCommands_.clear();
            queuedCommands_.clear();
            earlyResults_.clear();
            messageIdAssignmentsInFlight_ = 0;
        }
        eventListeners_.clear();
        try {
            if (registration != null) registration.close();
        } finally {
            for (CompletableFuture<String> future : pendingCommands) {
                future.completeExceptionally(new DevToolsException("Client is closed"));
            }
        }
    }

    public boolean isClosed() {
        synchronized (lifecycleLock_) {
            return registration_ == null;
        }
    }

    private void completeCommand(CompletableFuture<String> future, boolean success, String result) {
        synchronized (lifecycleLock_) {
            pendingCommands_.remove(future);
        }
        if (success) {
            future.complete(result);
        } else {
            future.completeExceptionally(new DevToolsException("DevTools method failed", result));
        }
    }

    private void completeCommandExceptionally(
            CompletableFuture<String> future, Throwable throwable) {
        synchronized (lifecycleLock_) {
            pendingCommands_.remove(future);
            queuedCommands_.values().remove(future);
        }
        future.completeExceptionally(throwable);
    }

    private void handleAgentDetached() {
        Set<CompletableFuture<String>> pendingCommands;
        synchronized (lifecycleLock_) {
            if (registration_ == null) return;
            pendingCommands = new LinkedHashSet<CompletableFuture<String>>(pendingCommands_);
            pendingCommands_.clear();
            queuedCommands_.clear();
            earlyResults_.clear();
        }
        for (CompletableFuture<String> future : pendingCommands) {
            future.completeExceptionally(new DevToolsException("DevTools agent detached"));
        }
    }

    private void trimEarlyResultsLocked() {
        while (earlyResults_.size() > MAX_BUFFERED_EARLY_RESULTS) {
            Integer oldestMessageId = earlyResults_.keySet().iterator().next();
            earlyResults_.remove(oldestMessageId);
        }
    }

    private void settleMessageIdAssignmentLocked() {
        if (messageIdAssignmentsInFlight_ > 0) messageIdAssignmentsInFlight_--;
    }

    private void clearUnmatchedEarlyResultsIfSettledLocked() {
        if (messageIdAssignmentsInFlight_ == 0) earlyResults_.clear();
    }

    /**
     * Execute a method call over the DevTools protocol. See the <a
     * href=https://chromedevtools.github.io/devtools-protocol/> DevTools protocol documentation</a>
     * for details of supported methods and the expected syntax for parameters.
     *
     * <p>If an error occurs the returned future is completed exceptionally, otherwise its value is
     * asynchronously set to the method result.
     *
     * <p>Call {@link #addEventListener(EventListener)} to subscribe to events.
     *
     * @param method the method name
     * @return return a future with the method result if the method was executed successfully
     */
    public CompletableFuture<String> executeDevToolsMethod(String method) {
        return executeDevToolsMethod(method, null);
    }

    /**
     * Execute a method call over the DevTools protocol. See the <a
     * href=https://chromedevtools.github.io/devtools-protocol/> DevTools protocol documentation</a>
     * for details of supported methods and the expected syntax for parameters.
     *
     * <p>If an error occurs the returned future is completed exceptionally, otherwise its value is
     * asynchronously set to the method result.
     *
     * <p>Call {@link #addEventListener(EventListener)} to subscribe to events.
     *
     * @param method the method name
     * @param parametersAsJson JSON object with parameters, or null if no parameters are needed
     * @return return a future with the method result if the method was executed successfully
     */
    public CompletableFuture<String> executeDevToolsMethod(String method, String parametersAsJson) {
        CompletableFuture<String> resultFuture = new CompletableFuture<String>();
        CompletableFuture<Integer> messageIdFuture;
        synchronized (lifecycleLock_) {
            if (registration_ == null) {
                resultFuture.completeExceptionally(new DevToolsException("Client is closed"));
                return resultFuture;
            }
            pendingCommands_.add(resultFuture);
            messageIdAssignmentsInFlight_++;
            try {
                messageIdFuture = methodExecutor_.apply(method, parametersAsJson);
            } catch (RuntimeException | Error error) {
                settleMessageIdAssignmentLocked();
                clearUnmatchedEarlyResultsIfSettledLocked();
                pendingCommands_.remove(resultFuture);
                resultFuture.completeExceptionally(error);
                return resultFuture;
            }
            if (messageIdFuture == null) {
                settleMessageIdAssignmentLocked();
                clearUnmatchedEarlyResultsIfSettledLocked();
                pendingCommands_.remove(resultFuture);
                resultFuture.completeExceptionally(new DevToolsException(
                        "DevTools method did not return a message ID future"));
                return resultFuture;
            }
        }

        messageIdFuture.whenComplete((messageId, throwable) -> {
            if (throwable != null) {
                boolean commandStillPending;
                synchronized (lifecycleLock_) {
                    settleMessageIdAssignmentLocked();
                    clearUnmatchedEarlyResultsIfSettledLocked();
                    commandStillPending = pendingCommands_.contains(resultFuture);
                }
                if (commandStillPending) completeCommandExceptionally(resultFuture, throwable);
                return;
            }

            MethodResult earlyResult = null;
            Throwable registrationFailure = null;
            synchronized (lifecycleLock_) {
                settleMessageIdAssignmentLocked();
                if (!pendingCommands_.contains(resultFuture)) {
                    clearUnmatchedEarlyResultsIfSettledLocked();
                    return;
                } else if (registration_ == null) {
                    registrationFailure = new DevToolsException("Client is closed");
                } else if (messageId == null || messageId <= 0) {
                    registrationFailure =
                            new DevToolsException("DevTools method returned an invalid message ID");
                } else if (queuedCommands_.containsKey(messageId)) {
                    registrationFailure = new DevToolsException(
                            "DevTools method returned a duplicate message ID");
                } else {
                    queuedCommands_.put(messageId, resultFuture);
                    earlyResult = earlyResults_.remove(messageId);
                    if (earlyResult != null) queuedCommands_.remove(messageId);
                }
                clearUnmatchedEarlyResultsIfSettledLocked();
            }
            if (registrationFailure != null) {
                completeCommandExceptionally(resultFuture, registrationFailure);
            } else if (earlyResult != null) {
                completeCommand(resultFuture, earlyResult.success_, earlyResult.result_);
            }
        });
        return resultFuture;
    }

    /**
     * Add an event listener for DevTools protocol events. Events by default are disabled
     * and need to be enabled on a per domain basis, e.g. by sending Network.enable to enable
     * network related events.
     *
     * @param eventListener the listener to add
     */
    public void addEventListener(EventListener eventListener) {
        eventListeners_.add(eventListener);
    }

    /**
     * Remove an event listener for DevTools protocol events.
     *
     * @param eventListener the listener to remove
     */
    public void removeEventListener(EventListener eventListener) {
        eventListeners_.remove(eventListener);
    }

    public interface EventListener {
        /**
         * Method that will be called on receipt of an event.
         * @param eventName the event name
         * @param messageAsJson JSON object with the event message
         */
        void onEvent(String eventName, String messageAsJson);
    }

    public static final class DevToolsException extends Exception {
        private static final long serialVersionUID = 3952948449841375372L;

        private final String json_;

        public DevToolsException(String message) {
            this(message, null);
        }

        public DevToolsException(String message, String json) {
            super(message);
            this.json_ = json;
        }

        @Override
        public String getMessage() {
            String message = super.getMessage();
            if (json_ != null) message += ": " + json_;
            return message;
        }

        /**
         * JSON object with the error details that were passed back by the DevTools
         * host, or null if no further details are available.
         */
        public String getJson() {
            return json_;
        }
    }
}
