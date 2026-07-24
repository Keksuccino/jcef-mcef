// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import java.util.Date;

/**
 * Class used to represent a download item.
 */
public interface CefDownloadItem {
    /**
     * Download interruption reasons. Values are pinned to
     * {@code cef_download_interrupt_reason_t} in CEF 151.
     */
    public enum DownloadInterruptReason {
        UNKNOWN(-1),
        CEF_DOWNLOAD_INTERRUPT_REASON_NONE(0),
        CEF_DOWNLOAD_INTERRUPT_REASON_FILE_FAILED(1),
        CEF_DOWNLOAD_INTERRUPT_REASON_FILE_ACCESS_DENIED(2),
        CEF_DOWNLOAD_INTERRUPT_REASON_FILE_NO_SPACE(3),
        CEF_DOWNLOAD_INTERRUPT_REASON_FILE_NAME_TOO_LONG(5),
        CEF_DOWNLOAD_INTERRUPT_REASON_FILE_TOO_LARGE(6),
        CEF_DOWNLOAD_INTERRUPT_REASON_FILE_VIRUS_INFECTED(7),
        CEF_DOWNLOAD_INTERRUPT_REASON_FILE_TRANSIENT_ERROR(10),
        CEF_DOWNLOAD_INTERRUPT_REASON_FILE_BLOCKED(11),
        CEF_DOWNLOAD_INTERRUPT_REASON_FILE_SECURITY_CHECK_FAILED(12),
        CEF_DOWNLOAD_INTERRUPT_REASON_FILE_TOO_SHORT(13),
        CEF_DOWNLOAD_INTERRUPT_REASON_FILE_HASH_MISMATCH(14),
        CEF_DOWNLOAD_INTERRUPT_REASON_FILE_SAME_AS_SOURCE(15),
        CEF_DOWNLOAD_INTERRUPT_REASON_NETWORK_FAILED(20),
        CEF_DOWNLOAD_INTERRUPT_REASON_NETWORK_TIMEOUT(21),
        CEF_DOWNLOAD_INTERRUPT_REASON_NETWORK_DISCONNECTED(22),
        CEF_DOWNLOAD_INTERRUPT_REASON_NETWORK_SERVER_DOWN(23),
        CEF_DOWNLOAD_INTERRUPT_REASON_NETWORK_INVALID_REQUEST(24),
        CEF_DOWNLOAD_INTERRUPT_REASON_SERVER_FAILED(30),
        CEF_DOWNLOAD_INTERRUPT_REASON_SERVER_NO_RANGE(31),
        CEF_DOWNLOAD_INTERRUPT_REASON_SERVER_BAD_CONTENT(33),
        CEF_DOWNLOAD_INTERRUPT_REASON_SERVER_UNAUTHORIZED(34),
        CEF_DOWNLOAD_INTERRUPT_REASON_SERVER_CERT_PROBLEM(35),
        CEF_DOWNLOAD_INTERRUPT_REASON_SERVER_FORBIDDEN(36),
        CEF_DOWNLOAD_INTERRUPT_REASON_SERVER_UNREACHABLE(37),
        CEF_DOWNLOAD_INTERRUPT_REASON_SERVER_CONTENT_LENGTH_MISMATCH(38),
        CEF_DOWNLOAD_INTERRUPT_REASON_SERVER_CROSS_ORIGIN_REDIRECT(39),
        CEF_DOWNLOAD_INTERRUPT_REASON_USER_CANCELED(40),
        CEF_DOWNLOAD_INTERRUPT_REASON_USER_SHUTDOWN(41),
        CEF_DOWNLOAD_INTERRUPT_REASON_CRASH(50);

        private static final DownloadInterruptReason[] BY_VALUE = createValueLookup();
        private final int value_;

        DownloadInterruptReason(int value) {
            value_ = value;
        }

        /** Returns the exact CEF numeric value, or -1 for the unknown sentinel. */
        public int getValue() {
            return value_;
        }

        /**
         * Map a native value without relying on ordinal positions. Unknown current or future
         * values map to {@link #UNKNOWN}.
         */
        public static DownloadInterruptReason fromValue(int value) {
            if (value < 0 || value >= BY_VALUE.length) {
                return UNKNOWN;
            }
            return BY_VALUE[value];
        }

        private static DownloadInterruptReason[] createValueLookup() {
            DownloadInterruptReason[] reasons = new DownloadInterruptReason[51];
            for (int i = 0; i < reasons.length; i++) {
                reasons[i] = UNKNOWN;
            }
            for (DownloadInterruptReason reason : values()) {
                if (reason.value_ < 0) {
                    continue;
                }
                if (reason.value_ >= reasons.length
                        || reasons[reason.value_] != UNKNOWN) {
                    throw new ExceptionInInitializerError("Duplicate or invalid download interrupt reason: " + reason.value_);
                }
                reasons[reason.value_] = reason;
            }
            return reasons;
        }
    }

    /**
     * Returns true if this object is valid. Do not call any other methods if this
     * function returns false.
     */
    boolean isValid();

    /**
     * Returns true if the download is in progress.
     */
    boolean isInProgress();

    /**
     * Returns true if the download is complete.
     */
    boolean isComplete();

    /**
     * Returns true if the download has been canceled.
     */
    boolean isCanceled();

    /**
     * Returns true if the download has been interrupted.
     */
    boolean isInterrupted();

    /**
     * Returns true if the download has been paused.
     */
    boolean isPaused();

    /**
     * Returns the most recent interrupt reason, mapping future native values to an explicit
     * unknown sentinel.
     */
    DownloadInterruptReason getInterruptReason();

    /**
     * Returns the unmodified native interrupt-reason value for forward-compatible diagnostics.
     */
    int getInterruptReasonValue();

    /**
     * Returns a simple speed estimate in bytes/s.
     */
    long getCurrentSpeed();

    /**
     * Returns the rough percent complete or -1 if the receive total size is
     * unknown.
     */
    int getPercentComplete();

    /**
     * Returns the total number of bytes.
     */
    long getTotalBytes();

    /**
     * Returns the number of received bytes.
     */
    long getReceivedBytes();

    /**
     * Returns the time that the download started.
     */
    Date getStartTime();

    /**
     * Returns the time that the download ended.
     */
    Date getEndTime();

    /**
     * Returns the full path to the downloaded or downloading file.
     */
    String getFullPath();

    /**
     * Returns the unique identifier for this download.
     */
    int getId();

    /**
     * Returns the URL.
     */
    String getURL();

    /**
     * Returns the original URL before any redirections.
     */
    String getOriginalURL();

    /**
     * Returns the suggested file name.
     */
    String getSuggestedFileName();

    /**
     * Returns the content disposition.
     */
    String getContentDisposition();

    /**
     * Returns the mime type.
     */
    String getMimeType();
}
