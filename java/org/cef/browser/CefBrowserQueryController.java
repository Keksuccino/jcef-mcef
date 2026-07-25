// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.browser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Owns asynchronous browser queries until native completion or browser close.
 *
 * <p>Native callbacks and {@code onBeforeClose} can race on different threads. Every terminal
 * path must claim a query through this controller before completing its future so close cannot
 * lose to a late native callback after it has already claimed that query.
 */
final class CefBrowserQueryController {
    static final class Query<T> {
        private final String operation_;
        private final CompletableFuture<T> future_ = new CompletableFuture<T>();
        private boolean accepted_;

        private Query(String operation) {
            operation_ = operation;
        }

        CompletableFuture<T> future() {
            return future_;
        }

        boolean wasAccepted() {
            return accepted_;
        }
    }

    private final Set<Query<?>> pendingQueries_ = new HashSet<Query<?>>();
    private boolean closed_;

    <T> Query<T> begin(String operation, boolean browserAvailable) {
        Objects.requireNonNull(operation, "operation");
        Query<T> query = new Query<T>(operation);
        boolean controllerClosed;
        synchronized (this) {
            controllerClosed = closed_;
            if (!closed_ && browserAvailable) {
                query.accepted_ = true;
                pendingQueries_.add(query);
            }
        }

        // CompletableFuture is mutable by its caller. Removing on every terminal path prevents a
        // caller cancellation or manual completion from being retained until browser close.
        query.future_.whenComplete((value, failure) -> discard(query));
        if (!query.accepted_) {
            query.future_.completeExceptionally(controllerClosed ? closedException(operation) : unavailableException(operation));
        }
        return query;
    }

    synchronized boolean isPending(Query<?> query) {
        return pendingQueries_.contains(query);
    }

    <T> void complete(Query<T> query, T value) {
        runTerminalAction(prepareCompletion(query, value));
    }

    void fail(Query<?> query, Throwable failure) {
        runTerminalAction(prepareFailure(query, failure));
    }

    <T> Runnable prepareCompletion(Query<T> query, T value) {
        Objects.requireNonNull(query, "query");
        return claim(query) ? () -> query.future_.complete(value) : null;
    }

    Runnable prepareFailure(Query<?> query, Throwable failure) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(failure, "failure");
        return claim(query) ? () -> query.future_.completeExceptionally(failure) : null;
    }

    void close() {
        List<Query<?>> pendingQueries;
        synchronized (this) {
            if (closed_) return;
            closed_ = true;
            pendingQueries = new ArrayList<Query<?>>(pendingQueries_);
            pendingQueries_.clear();
        }

        // Never invoke CompletableFuture continuations while holding the lifecycle lock. User
        // continuations may immediately reenter browser code.
        for (Query<?> query : pendingQueries) {
            query.future_.completeExceptionally(closedException(query.operation_));
        }
    }

    synchronized int pendingCountForTesting() {
        return pendingQueries_.size();
    }

    synchronized boolean isClosedForTesting() {
        return closed_;
    }

    private synchronized boolean claim(Query<?> query) {
        return pendingQueries_.remove(query);
    }

    private synchronized void discard(Query<?> query) {
        pendingQueries_.remove(query);
    }

    private static void runTerminalAction(Runnable terminalAction) {
        if (terminalAction != null) terminalAction.run();
    }

    private static IllegalStateException closedException(String operation) {
        return new IllegalStateException("Browser closed before " + operation + " completed");
    }

    private static IllegalStateException unavailableException(String operation) {
        return new IllegalStateException("Native browser is unavailable for " + operation);
    }
}
