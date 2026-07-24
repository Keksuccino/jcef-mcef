// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandler.ErrorCode;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefHeader;
import org.cef.network.CefPostData;
import org.cef.network.CefPostDataElement;
import org.cef.network.CefRequest;
import org.cef.network.CefRequest.CefUrlRequestFlags;
import org.cef.network.CefRequest.ReferrerPolicy;
import org.cef.network.CefRequest.Transition;
import org.cef.network.CefRequest.TransitionFlags;
import org.cef.network.CefRequest.TransitionType;
import org.cef.network.CefResponse;
import org.cef.network.CefURLRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class CefNetworkValueTest {
    @Test
    void urlRequestFlagsExactlyMatchCef151AndRetainSafeAliases() {
        assertEquals(0, CefUrlRequestFlags.UR_FLAG_NONE);
        assertEquals(1, CefUrlRequestFlags.UR_FLAG_SKIP_CACHE);
        assertEquals(2, CefUrlRequestFlags.UR_FLAG_ONLY_FROM_CACHE);
        assertEquals(4, CefUrlRequestFlags.UR_FLAG_DISABLE_CACHE);
        assertEquals(8, CefUrlRequestFlags.UR_FLAG_ALLOW_STORED_CREDENTIALS);
        assertEquals(16, CefUrlRequestFlags.UR_FLAG_REPORT_UPLOAD_PROGRESS);
        assertEquals(32, CefUrlRequestFlags.UR_FLAG_NO_DOWNLOAD_DATA);
        assertEquals(64, CefUrlRequestFlags.UR_FLAG_NO_RETRY_ON_5XX);
        assertEquals(128, CefUrlRequestFlags.UR_FLAG_STOP_ON_REDIRECT);
        assertEquals(0xFF, CefUrlRequestFlags.UR_FLAG_KNOWN_MASK);
        assertEquals(CefUrlRequestFlags.UR_FLAG_ALLOW_STORED_CREDENTIALS,
                CefUrlRequestFlags.UR_FLAG_ALLOW_CACHED_CREDENTIALS);
        assertEquals(
                CefUrlRequestFlags.UR_FLAG_NONE, CefUrlRequestFlags.UR_FLAG_REPORT_RAW_HEADERS);
        assertEquals(0x7F00, CefUrlRequestFlags.getUnknownFlags(0x7FFF));
    }

    @Test
    void transitionSourcesQualifiersAndUnknownBitsExactlyMatchCef151() {
        assertTransitionSource(TransitionType.TT_LINK, 0);
        assertTransitionSource(TransitionType.TT_EXPLICIT, 1);
        assertTransitionSource(TransitionType.TT_AUTO_BOOKMARK, 2);
        assertTransitionSource(TransitionType.TT_AUTO_SUBFRAME, 3);
        assertTransitionSource(TransitionType.TT_MANUAL_SUBFRAME, 4);
        assertTransitionSource(TransitionType.TT_GENERATED, 5);
        assertTransitionSource(TransitionType.TT_AUTO_TOPLEVEL, 6);
        assertTransitionSource(TransitionType.TT_FORM_SUBMIT, 7);
        assertTransitionSource(TransitionType.TT_RELOAD, 8);
        assertTransitionSource(TransitionType.TT_KEYWORD, 9);
        assertTransitionSource(TransitionType.TT_KEYWORD_GENERATED, 10);
        assertTransitionSource(TransitionType.TT_NUM_VALUES, 11);
        assertEquals(12, TransitionType.values().length);

        assertTransitionFlag(TransitionFlags.TT_BLOCKED_FLAG, 0x00800000);
        assertTransitionFlag(TransitionFlags.TT_FORWARD_BACK_FLAG, 0x01000000);
        assertTransitionFlag(TransitionFlags.TT_DIRECT_LOAD_FLAG, 0x02000000);
        assertTransitionFlag(TransitionFlags.TT_HOME_PAGE_FLAG, 0x04000000);
        assertTransitionFlag(TransitionFlags.TT_FROM_API_FLAG, 0x08000000);
        assertTransitionFlag(TransitionFlags.TT_CHAIN_START_FLAG, 0x10000000);
        assertTransitionFlag(TransitionFlags.TT_CHAIN_END_FLAG, 0x20000000);
        assertTransitionFlag(TransitionFlags.TT_CLIENT_REDIRECT_FLAG, 0x40000000);
        assertTransitionFlag(TransitionFlags.TT_SERVER_REDIRECT_FLAG, 0x80000000);

        int raw = 0x8040007F;
        Transition unknown = Transition.fromRawValue(raw);
        assertEquals(raw, unknown.getValue());
        assertEquals(0x7F, unknown.getSource());
        assertEquals(raw & 0xFFFFFF00, unknown.getQualifiers());
        assertNull(unknown.getType());
        assertTrue(unknown.getKnownType().isEmpty());
        assertTrue(unknown.isRedirect());
        assertFalse(unknown.isSet(TransitionFlags.TT_CLIENT_REDIRECT_FLAG));
        assertTrue(unknown.isSet(TransitionFlags.TT_SERVER_REDIRECT_FLAG));

        Transition known =
                TransitionType.TT_FORM_SUBMIT.withQualifier(TransitionFlags.TT_FORWARD_BACK_FLAG)
                        .withQualifier(TransitionFlags.TT_SERVER_REDIRECT_FLAG);
        assertEquals(0x81000007, known.getValue());
        assertSame(TransitionType.TT_FORM_SUBMIT, known.getType());
        assertEquals(0x81000000, known.getQualifiers());
        assertEquals(known, known.withQualifiers(0x000000AB));
        assertEquals(0x80000007,
                known.withoutQualifier(TransitionFlags.TT_FORWARD_BACK_FLAG).getValue());
    }

    @Test
    void transitionEnumSingletonsCannotLeakPerEventQualifiers() {
        TransitionType type = TransitionType.TT_LINK;
        assertThrows(UnsupportedOperationException.class,
                () -> type.addQualifier(TransitionFlags.TT_SERVER_REDIRECT_FLAG));
        assertThrows(UnsupportedOperationException.class,
                () -> type.addQualifiers(TransitionFlags.TT_CHAIN_START_FLAG.getValue()));
        assertThrows(UnsupportedOperationException.class,
                () -> type.removeQualifier(TransitionFlags.TT_CHAIN_END_FLAG));
        assertEquals(0, type.getValue());
        assertEquals(0, type.getQualifiers());
        assertFalse(type.isRedirect());
    }

    @Test
    void loadHandlerRawOverloadsPreserveBitsAndLegacyDefaultsRemainCompatible() {
        int transitionValue = 0xC1800007;
        int unknownError = -1_234_567;
        Transition transition = Transition.fromRawValue(transitionValue);
        AtomicReference<Transition> rawTransition = new AtomicReference<Transition>();
        AtomicInteger rawError = new AtomicInteger();
        CefLoadHandler rawAware = new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser browser, CefFrame frame, Transition value) {
                rawTransition.set(value);
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame frame, int errorCode,
                    String errorText, String failedUrl) {
                rawError.set(errorCode);
            }
        };

        rawAware.onLoadStart(null, null, transition);
        rawAware.onLoadError(null, null, unknownError, "future", "https://invalid.test");
        assertSame(transition, rawTransition.get());
        assertEquals(transitionValue, rawTransition.get().getValue());
        assertEquals(unknownError, rawError.get());

        AtomicReference<TransitionType> legacyTransition = new AtomicReference<TransitionType>();
        AtomicReference<ErrorCode> legacyError = new AtomicReference<ErrorCode>();
        CefLoadHandler legacyOnly = new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(
                    CefBrowser browser, CefFrame frame, TransitionType transitionType) {
                legacyTransition.set(transitionType);
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode,
                    String errorText, String failedUrl) {
                legacyError.set(errorCode);
            }
        };

        legacyOnly.onLoadStart(null, null, transition);
        legacyOnly.onLoadError(
                null, null, ErrorCode.ERR_FAILED.getCode(), "known", "https://known.test");
        assertSame(TransitionType.TT_FORM_SUBMIT, legacyTransition.get());
        assertSame(ErrorCode.ERR_FAILED, legacyError.get());
        legacyOnly.onLoadStart(null, null, Transition.fromRawValue(0x0000007F));
        legacyOnly.onLoadError(null, null, unknownError, "future", "https://invalid.test");
        assertNull(legacyTransition.get());
        assertNull(legacyError.get());
    }

    @Test
    void errorCodesExactlyMatchTheCef151ActiveList() {
        Map<String, Integer> expected = parseCodes(CEF151_ACTIVE_ERROR_CODES);
        Map<String, Integer> actual = new LinkedHashMap<String, Integer>();
        for (ErrorCode errorCode : ErrorCode.values()) {
            if (!isDeprecated(errorCode)) actual.put(errorCode.name(), errorCode.getCode());
        }

        assertEquals(249, expected.size());
        assertEquals(expected, actual);
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            ErrorCode errorCode = ErrorCode.valueOf(entry.getKey());
            assertEquals(entry.getValue().intValue(), errorCode.getCode(), entry.getKey());
            assertSame(
                    errorCode, ErrorCode.findByCode(entry.getValue().intValue()), entry.getKey());
        }
        assertNull(ErrorCode.findByCode(Integer.MIN_VALUE));
        assertNull(ErrorCode.findByCode(Integer.MAX_VALUE));
    }

    @Test
    void historicalErrorSymbolsRemainDeprecatedAndCannotShadowActiveMappings() {
        Map<String, Integer> active = parseCodes(CEF151_ACTIVE_ERROR_CODES);
        Map<String, Integer> legacy = parseCodes(HISTORICAL_ERROR_CODES);
        assertEquals(active.size() + legacy.size(), ErrorCode.values().length);

        int firstLegacyOrdinal = ErrorCode.values().length;
        for (Map.Entry<String, Integer> entry : legacy.entrySet()) {
            ErrorCode errorCode = ErrorCode.valueOf(entry.getKey());
            assertTrue(isDeprecated(errorCode), entry.getKey());
            assertEquals(entry.getValue().intValue(), errorCode.getCode(), entry.getKey());
            firstLegacyOrdinal = Math.min(firstLegacyOrdinal, errorCode.ordinal());
            ErrorCode mapped = ErrorCode.findByCode(entry.getValue().intValue());
            if (active.containsValue(entry.getValue())) {
                assertFalse(isDeprecated(mapped), entry.getKey());
            } else {
                assertSame(errorCode, mapped, entry.getKey());
            }
        }
        for (ErrorCode errorCode : ErrorCode.values()) {
            if (!isDeprecated(errorCode))
                assertTrue(errorCode.ordinal() < firstLegacyOrdinal, errorCode.name());
        }
    }

    @Test
    void duplicateHeaderBridgePreservesOrderAndAppendsAtomically() {
        List<CefHeader> input = List.of(new CefHeader("Set-Cookie", "first=1"),
                new CefHeader("Set-Cookie", "second=2"), new CefHeader("X-Test", "value"));
        String[] pairs = toNativePairs(input);
        assertArrayEquals(
                new String[] {"Set-Cookie", "first=1", "Set-Cookie", "second=2", "X-Test", "value"},
                pairs);

        List<CefHeader> output = new ArrayList<CefHeader>();
        output.add(new CefHeader("Existing", "before"));
        addNativePairs(output, pairs);
        assertEquals(
                List.of(new CefHeader("Existing", "before"), new CefHeader("Set-Cookie", "first=1"),
                        new CefHeader("Set-Cookie", "second=2"), new CefHeader("X-Test", "value")),
                output);

        List<CefHeader> unchanged =
                new ArrayList<CefHeader>(List.of(new CefHeader("Existing", "before")));
        assertThrows(NullPointerException.class,
                () -> addNativePairs(unchanged, new String[] {"Valid", "value", null, "invalid"}));
        assertEquals(List.of(new CefHeader("Existing", "before")), unchanged);
    }

    @Test
    void headerBridgeRejectsNullAndMalformedInputs() {
        assertThrows(NullPointerException.class, () -> new CefHeader(null, "value"));
        assertThrows(NullPointerException.class, () -> new CefHeader("name", null));
        assertThrows(NullPointerException.class, () -> toNativePairs(null));
        assertThrows(
                NullPointerException.class, () -> toNativePairs(Arrays.asList((CefHeader) null)));
        assertThrows(
                NullPointerException.class, () -> addNativePairs(new ArrayList<CefHeader>(), null));
        assertThrows(IllegalArgumentException.class,
                () -> addNativePairs(new ArrayList<CefHeader>(), new String[] {"name"}));
        assertThrows(NullPointerException.class,
                () -> addNativePairs(new ArrayList<CefHeader>(), new String[] {"name", null}));
    }

    @Test
    void postDataByteRangesRejectInvalidSizesBeforeJni() {
        validateByteRange(0, new byte[0]);
        validateByteRange(0, new byte[3]);
        validateByteRange(3, new byte[3]);
        assertThrows(NullPointerException.class, () -> validateByteRange(0, null));
        assertThrows(IllegalArgumentException.class, () -> validateByteRange(-1, new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> validateByteRange(4, new byte[3]));
    }

    @Test
    void forwardCompatibleNetworkMethodsArePresentOnJavaAndClientSurfaces() throws Exception {
        assertAbstractMethod(CefRequest.class, "getHeaderList", List.class);
        assertAbstractMethod(CefRequest.class, "setHeaderList", List.class);
        assertAbstractMethod(CefRequest.class, "getTransitionTypeValue");
        assertAbstractMethod(CefResponse.class, "getErrorCode");
        assertAbstractMethod(CefResponse.class, "setErrorCode", int.class);
        assertAbstractMethod(CefResponse.class, "getCharset");
        assertAbstractMethod(CefResponse.class, "setCharset", String.class);
        assertAbstractMethod(CefResponse.class, "getHeaderList", List.class);
        assertAbstractMethod(CefResponse.class, "setHeaderList", List.class);
        assertAbstractMethod(CefResponse.class, "getURL");
        assertAbstractMethod(CefResponse.class, "setURL", String.class);
        assertAbstractMethod(CefURLRequest.class, "getRequestErrorCode");
        assertAbstractMethod(CefPostData.class, "hasExcludedElements");
        assertEquals(void.class,
                CefClient.class
                        .getMethod(
                                "onLoadStart", CefBrowser.class, CefFrame.class, Transition.class)
                        .getReturnType());
        assertEquals(void.class,
                CefClient.class
                        .getMethod("onLoadError", CefBrowser.class, CefFrame.class, int.class,
                                String.class, String.class)
                        .getReturnType());
        assertTrue(ReferrerPolicy.class.getField("REFERRER_POLICY_LAST_VALUE")
                           .isAnnotationPresent(Deprecated.class));
    }

    private static void assertTransitionSource(TransitionType transitionType, int expected) {
        assertEquals(expected, transitionType.getSource());
        assertEquals(expected, transitionType.getValue());
        assertSame(transitionType,
                TransitionType.fromRawValue(
                        expected | TransitionFlags.TT_CHAIN_START_FLAG.getValue()));
    }

    private static void assertTransitionFlag(TransitionFlags transitionFlag, int expected) {
        assertEquals(expected, transitionFlag.getValue());
    }

    private static boolean isDeprecated(ErrorCode errorCode) {
        try {
            return ErrorCode.class.getField(errorCode.name()).isAnnotationPresent(Deprecated.class);
        } catch (NoSuchFieldException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Map<String, Integer> parseCodes(String[] codes) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (String line : codes) {
            int separator = line.lastIndexOf('=');
            result.put(
                    line.substring(0, separator), Integer.valueOf(line.substring(separator + 1)));
        }
        return result;
    }

    private static String[] toNativePairs(List<CefHeader> headers) {
        try {
            Method method = CefHeader.class.getDeclaredMethod("toNativePairs", List.class);
            method.setAccessible(true);
            return (String[]) method.invoke(null, headers);
        } catch (InvocationTargetException exception) {
            throw propagate(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void addNativePairs(List<CefHeader> headers, String[] pairs) {
        try {
            Method method =
                    CefHeader.class.getDeclaredMethod("addNativePairs", List.class, String[].class);
            method.setAccessible(true);
            method.invoke(null, headers, pairs);
        } catch (InvocationTargetException exception) {
            throw propagate(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void validateByteRange(int size, byte[] bytes) {
        try {
            Method method = CefPostDataElement.class.getDeclaredMethod(
                    "validateByteRange", int.class, byte[].class);
            method.setAccessible(true);
            method.invoke(null, size, bytes);
        } catch (InvocationTargetException exception) {
            throw propagate(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException) return (RuntimeException) throwable;
        if (throwable instanceof Error) throw(Error) throwable;
        throw new AssertionError(throwable);
    }

    private static void assertAbstractMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws Exception {
        assertTrue(Modifier.isAbstract(type.getMethod(name, parameterTypes).getModifiers()),
                type.getName() + "." + name);
    }

    private static final String[] HISTORICAL_ERROR_CODES = {"ERR_NO_SSL_VERSIONS_ENABLED=-112",
            "ERR_HTTPS_PROXY_TUNNEL_RESPONSE_REDIRECT=-140", "ERR_SSL_HANDSHAKE_NOT_COMPLETED=-148",
            "ERR_SSL_BAD_PEER_PUBLIC_KEY=-149", "ERR_CERT_SYMANTEC_LEGACY=-215",
            "ERR_SYN_REPLY_NOT_RECEIVED=-332", "ERR_ENCODING_CONVERSION_FAILED=-333",
            "ERR_UNRECOGNIZED_FTP_DIRECTORY_LISTING_FORMAT=-334",
            "ERR_HTTP2_PUSHED_STREAM_NOT_AVAILABLE=-373",
            "ERR_HTTP2_CLAIMED_PUSHED_STREAM_RESET_BY_SERVER=-374",
            "ERR_HTTP2_CLIENT_REFUSED_STREAM=-377", "ERR_HTTP2_PUSHED_RESPONSE_DOES_NOT_MATCH=-378",
            "ERR_FTP_FAILED=-601", "ERR_FTP_SERVICE_UNAVAILABLE=-602",
            "ERR_FTP_TRANSFER_ABORTED=-603", "ERR_FTP_FILE_BUSY=-604", "ERR_FTP_SYNTAX_ERROR=-605",
            "ERR_FTP_COMMAND_NOT_SUPPORTED=-606", "ERR_FTP_BAD_COMMAND_SEQUENCE=-607",
            "ERR_DNS_SERVER_FAILED=-802"};

    private static final String[] CEF151_ACTIVE_ERROR_CODES = {"ERR_NONE=0", "ERR_IO_PENDING=-1",
            "ERR_FAILED=-2", "ERR_ABORTED=-3", "ERR_INVALID_ARGUMENT=-4", "ERR_INVALID_HANDLE=-5",
            "ERR_FILE_NOT_FOUND=-6", "ERR_TIMED_OUT=-7", "ERR_FILE_TOO_BIG=-8", "ERR_UNEXPECTED=-9",
            "ERR_ACCESS_DENIED=-10", "ERR_NOT_IMPLEMENTED=-11", "ERR_INSUFFICIENT_RESOURCES=-12",
            "ERR_OUT_OF_MEMORY=-13", "ERR_UPLOAD_FILE_CHANGED=-14", "ERR_SOCKET_NOT_CONNECTED=-15",
            "ERR_FILE_EXISTS=-16", "ERR_FILE_PATH_TOO_LONG=-17", "ERR_FILE_NO_SPACE=-18",
            "ERR_FILE_VIRUS_INFECTED=-19", "ERR_BLOCKED_BY_CLIENT=-20", "ERR_NETWORK_CHANGED=-21",
            "ERR_BLOCKED_BY_ADMINISTRATOR=-22", "ERR_SOCKET_IS_CONNECTED=-23",
            "ERR_UPLOAD_STREAM_REWIND_NOT_SUPPORTED=-25", "ERR_CONTEXT_SHUT_DOWN=-26",
            "ERR_BLOCKED_BY_RESPONSE=-27", "ERR_CLEARTEXT_NOT_PERMITTED=-29",
            "ERR_BLOCKED_BY_CSP=-30", "ERR_BLOCKED_BY_ORB=-32", "ERR_NETWORK_ACCESS_REVOKED=-33",
            "ERR_BLOCKED_BY_FINGERPRINTING_PROTECTION=-34",
            "ERR_BLOCKED_IN_INCOGNITO_BY_ADMINISTRATOR=-35",
            "ERR_LOCAL_NETWORK_PERMISSION_MISSING=-36", "ERR_STRICT_ECH_REQUIRED=-37",
            "ERR_CONNECTION_CLOSED=-100", "ERR_CONNECTION_RESET=-101",
            "ERR_CONNECTION_REFUSED=-102", "ERR_CONNECTION_ABORTED=-103",
            "ERR_CONNECTION_FAILED=-104", "ERR_NAME_NOT_RESOLVED=-105",
            "ERR_INTERNET_DISCONNECTED=-106", "ERR_SSL_PROTOCOL_ERROR=-107",
            "ERR_ADDRESS_INVALID=-108", "ERR_ADDRESS_UNREACHABLE=-109",
            "ERR_SSL_CLIENT_AUTH_CERT_NEEDED=-110", "ERR_TUNNEL_CONNECTION_FAILED=-111",
            "ERR_SSL_VERSION_OR_CIPHER_MISMATCH=-113", "ERR_SSL_RENEGOTIATION_REQUESTED=-114",
            "ERR_PROXY_AUTH_UNSUPPORTED=-115", "ERR_BAD_SSL_CLIENT_AUTH_CERT=-117",
            "ERR_CONNECTION_TIMED_OUT=-118", "ERR_HOST_RESOLVER_QUEUE_TOO_LARGE=-119",
            "ERR_SOCKS_CONNECTION_FAILED=-120", "ERR_SOCKS_CONNECTION_HOST_UNREACHABLE=-121",
            "ERR_ALPN_NEGOTIATION_FAILED=-122", "ERR_SSL_NO_RENEGOTIATION=-123",
            "ERR_WINSOCK_UNEXPECTED_WRITTEN_BYTES=-124", "ERR_SSL_DECOMPRESSION_FAILURE_ALERT=-125",
            "ERR_SSL_BAD_RECORD_MAC_ALERT=-126", "ERR_PROXY_AUTH_REQUESTED=-127",
            "ERR_PROXY_CONNECTION_FAILED=-130", "ERR_MANDATORY_PROXY_CONFIGURATION_FAILED=-131",
            "ERR_PRECONNECT_MAX_SOCKET_LIMIT=-133",
            "ERR_SSL_CLIENT_AUTH_PRIVATE_KEY_ACCESS_DENIED=-134",
            "ERR_SSL_CLIENT_AUTH_CERT_NO_PRIVATE_KEY=-135", "ERR_PROXY_CERTIFICATE_INVALID=-136",
            "ERR_NAME_RESOLUTION_FAILED=-137", "ERR_NETWORK_ACCESS_DENIED=-138",
            "ERR_TEMPORARILY_THROTTLED=-139", "ERR_SSL_CLIENT_AUTH_SIGNATURE_FAILED=-141",
            "ERR_MSG_TOO_BIG=-142", "ERR_WS_PROTOCOL_ERROR=-145", "ERR_ADDRESS_IN_USE=-147",
            "ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN=-150",
            "ERR_CLIENT_AUTH_CERT_TYPE_UNSUPPORTED=-151", "ERR_SSL_DECRYPT_ERROR_ALERT=-153",
            "ERR_WS_THROTTLE_QUEUE_TOO_LARGE=-154", "ERR_SSL_SERVER_CERT_CHANGED=-156",
            "ERR_SSL_UNRECOGNIZED_NAME_ALERT=-159", "ERR_SOCKET_SET_RECEIVE_BUFFER_SIZE_ERROR=-160",
            "ERR_SOCKET_SET_SEND_BUFFER_SIZE_ERROR=-161",
            "ERR_SOCKET_RECEIVE_BUFFER_SIZE_UNCHANGEABLE=-162",
            "ERR_SOCKET_SEND_BUFFER_SIZE_UNCHANGEABLE=-163",
            "ERR_SSL_CLIENT_AUTH_CERT_BAD_FORMAT=-164", "ERR_ICANN_NAME_COLLISION=-166",
            "ERR_SSL_SERVER_CERT_BAD_FORMAT=-167", "ERR_CT_STH_PARSING_FAILED=-168",
            "ERR_CT_STH_INCOMPLETE=-169", "ERR_UNABLE_TO_REUSE_CONNECTION_FOR_PROXY_AUTH=-170",
            "ERR_CT_CONSISTENCY_PROOF_PARSING_FAILED=-171", "ERR_SSL_OBSOLETE_CIPHER=-172",
            "ERR_WS_UPGRADE=-173", "ERR_READ_IF_READY_NOT_IMPLEMENTED=-174",
            "ERR_NO_BUFFER_SPACE=-176", "ERR_SSL_CLIENT_AUTH_NO_COMMON_ALGORITHMS=-177",
            "ERR_EARLY_DATA_REJECTED=-178", "ERR_WRONG_VERSION_ON_EARLY_DATA=-179",
            "ERR_TLS13_DOWNGRADE_DETECTED=-180", "ERR_SSL_KEY_USAGE_INCOMPATIBLE=-181",
            "ERR_INVALID_ECH_CONFIG_LIST=-182", "ERR_ECH_NOT_NEGOTIATED=-183",
            "ERR_ECH_FALLBACK_CERTIFICATE_INVALID=-184",
            "ERR_PROXY_UNABLE_TO_CONNECT_TO_DESTINATION=-186",
            "ERR_PROXY_DELEGATE_CANCELED_CONNECT_REQUEST=-187",
            "ERR_PROXY_DELEGATE_CANCELED_CONNECT_RESPONSE=-188", "ERR_CONTROL_MSG_TOO_BIG=-189",
            "ERR_CERT_COMMON_NAME_INVALID=-200", "ERR_CERT_DATE_INVALID=-201",
            "ERR_CERT_AUTHORITY_INVALID=-202", "ERR_CERT_CONTAINS_ERRORS=-203",
            "ERR_CERT_NO_REVOCATION_MECHANISM=-204", "ERR_CERT_UNABLE_TO_CHECK_REVOCATION=-205",
            "ERR_CERT_REVOKED=-206", "ERR_CERT_INVALID=-207",
            "ERR_CERT_WEAK_SIGNATURE_ALGORITHM=-208", "ERR_CERT_NON_UNIQUE_NAME=-210",
            "ERR_CERT_WEAK_KEY=-211", "ERR_CERT_NAME_CONSTRAINT_VIOLATION=-212",
            "ERR_CERT_VALIDITY_TOO_LONG=-213", "ERR_CERTIFICATE_TRANSPARENCY_REQUIRED=-214",
            "ERR_CERT_KNOWN_INTERCEPTION_BLOCKED=-217", "ERR_CERT_SELF_SIGNED_LOCAL_NETWORK=-219",
            "ERR_CERT_END=-220", "ERR_INVALID_URL=-300", "ERR_DISALLOWED_URL_SCHEME=-301",
            "ERR_UNKNOWN_URL_SCHEME=-302", "ERR_INVALID_REDIRECT=-303",
            "ERR_TOO_MANY_REDIRECTS=-310", "ERR_UNSAFE_REDIRECT=-311", "ERR_UNSAFE_PORT=-312",
            "ERR_INVALID_RESPONSE=-320", "ERR_INVALID_CHUNKED_ENCODING=-321",
            "ERR_METHOD_NOT_SUPPORTED=-322", "ERR_UNEXPECTED_PROXY_AUTH=-323",
            "ERR_EMPTY_RESPONSE=-324", "ERR_RESPONSE_HEADERS_TOO_BIG=-325",
            "ERR_PAC_SCRIPT_FAILED=-327", "ERR_REQUEST_RANGE_NOT_SATISFIABLE=-328",
            "ERR_MALFORMED_IDENTITY=-329", "ERR_CONTENT_DECODING_FAILED=-330",
            "ERR_NETWORK_IO_SUSPENDED=-331", "ERR_NO_SUPPORTED_PROXIES=-336",
            "ERR_HTTP2_PROTOCOL_ERROR=-337", "ERR_INVALID_AUTH_CREDENTIALS=-338",
            "ERR_UNSUPPORTED_AUTH_SCHEME=-339", "ERR_ENCODING_DETECTION_FAILED=-340",
            "ERR_MISSING_AUTH_CREDENTIALS=-341", "ERR_UNEXPECTED_SECURITY_LIBRARY_STATUS=-342",
            "ERR_MISCONFIGURED_AUTH_ENVIRONMENT=-343",
            "ERR_UNDOCUMENTED_SECURITY_LIBRARY_STATUS=-344",
            "ERR_RESPONSE_BODY_TOO_BIG_TO_DRAIN=-345",
            "ERR_RESPONSE_HEADERS_MULTIPLE_CONTENT_LENGTH=-346",
            "ERR_INCOMPLETE_HTTP2_HEADERS=-347", "ERR_PAC_NOT_IN_DHCP=-348",
            "ERR_RESPONSE_HEADERS_MULTIPLE_CONTENT_DISPOSITION=-349",
            "ERR_RESPONSE_HEADERS_MULTIPLE_LOCATION=-350", "ERR_HTTP2_SERVER_REFUSED_STREAM=-351",
            "ERR_HTTP2_PING_FAILED=-352", "ERR_CONTENT_LENGTH_MISMATCH=-354",
            "ERR_INCOMPLETE_CHUNKED_ENCODING=-355", "ERR_QUIC_PROTOCOL_ERROR=-356",
            "ERR_RESPONSE_HEADERS_TRUNCATED=-357", "ERR_QUIC_HANDSHAKE_FAILED=-358",
            "ERR_HTTP2_INADEQUATE_TRANSPORT_SECURITY=-360", "ERR_HTTP2_FLOW_CONTROL_ERROR=-361",
            "ERR_HTTP2_FRAME_SIZE_ERROR=-362", "ERR_HTTP2_COMPRESSION_ERROR=-363",
            "ERR_PROXY_AUTH_REQUESTED_WITH_NO_CONNECTION=-364", "ERR_HTTP_1_1_REQUIRED=-365",
            "ERR_PROXY_HTTP_1_1_REQUIRED=-366", "ERR_PAC_SCRIPT_TERMINATED=-367",
            "ERR_INVALID_HTTP_RESPONSE=-370", "ERR_CONTENT_DECODING_INIT_FAILED=-371",
            "ERR_HTTP2_RST_STREAM_NO_ERROR_RECEIVED=-372", "ERR_TOO_MANY_RETRIES=-375",
            "ERR_HTTP2_STREAM_CLOSED=-376", "ERR_HTTP_RESPONSE_CODE_FAILURE=-379",
            "ERR_QUIC_CERT_ROOT_NOT_KNOWN=-380", "ERR_QUIC_GOAWAY_REQUEST_CAN_BE_RETRIED=-381",
            "ERR_TOO_MANY_ACCEPT_CH_RESTARTS=-382", "ERR_INCONSISTENT_IP_ADDRESS_SPACE=-383",
            "ERR_CACHED_IP_ADDRESS_SPACE_BLOCKED_BY_LOCAL_NETWORK_ACCESS_POLICY=-384",
            "ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS=-385", "ERR_ZSTD_WINDOW_SIZE_TOO_BIG=-386",
            "ERR_DICTIONARY_LOAD_FAILED=-387", "ERR_UNEXPECTED_CONTENT_DICTIONARY_HEADER=-388",
            "ERR_CACHE_MISS=-400", "ERR_CACHE_READ_FAILURE=-401", "ERR_CACHE_WRITE_FAILURE=-402",
            "ERR_CACHE_OPERATION_NOT_SUPPORTED=-403", "ERR_CACHE_OPEN_FAILURE=-404",
            "ERR_CACHE_CREATE_FAILURE=-405", "ERR_CACHE_RACE=-406",
            "ERR_CACHE_CHECKSUM_READ_FAILURE=-407", "ERR_CACHE_CHECKSUM_MISMATCH=-408",
            "ERR_CACHE_LOCK_TIMEOUT=-409", "ERR_CACHE_AUTH_FAILURE_AFTER_READ=-410",
            "ERR_CACHE_ENTRY_NOT_SUITABLE=-411", "ERR_CACHE_DOOM_FAILURE=-412",
            "ERR_CACHE_OPEN_OR_CREATE_FAILURE=-413", "ERR_CACHE_COMPRESSION_FAILURE=-414",
            "ERR_INSECURE_RESPONSE=-501", "ERR_NO_PRIVATE_KEY_FOR_CERT=-502",
            "ERR_ADD_USER_CERT_FAILED=-503", "ERR_INVALID_SIGNED_EXCHANGE=-504",
            "ERR_INVALID_WEB_BUNDLE=-505", "ERR_TRUST_TOKEN_OPERATION_FAILED=-506",
            "ERR_TRUST_TOKEN_OPERATION_SUCCESS_WITHOUT_SENDING_REQUEST=-507",
            "ERR_HTTPENGINE_PROVIDER_IN_USE=-508", "ERR_PKCS12_IMPORT_BAD_PASSWORD=-701",
            "ERR_PKCS12_IMPORT_FAILED=-702", "ERR_IMPORT_CA_CERT_NOT_CA=-703",
            "ERR_IMPORT_CERT_ALREADY_EXISTS=-704", "ERR_IMPORT_CA_CERT_FAILED=-705",
            "ERR_IMPORT_SERVER_CERT_FAILED=-706", "ERR_PKCS12_IMPORT_INVALID_MAC=-707",
            "ERR_PKCS12_IMPORT_INVALID_FILE=-708", "ERR_PKCS12_IMPORT_UNSUPPORTED=-709",
            "ERR_KEY_GENERATION_FAILED=-710", "ERR_PRIVATE_KEY_EXPORT_FAILED=-712",
            "ERR_SELF_SIGNED_CERT_GENERATION_FAILED=-713", "ERR_CERT_DATABASE_CHANGED=-714",
            "ERR_CERT_VERIFIER_CHANGED=-716", "ERR_DNS_MALFORMED_RESPONSE=-800",
            "ERR_DNS_SERVER_REQUIRES_TCP=-801", "ERR_DNS_TIMED_OUT=-803", "ERR_DNS_CACHE_MISS=-804",
            "ERR_DNS_SEARCH_EMPTY=-805", "ERR_DNS_SORT_ERROR=-806",
            "ERR_DNS_SECURE_RESOLVER_HOSTNAME_RESOLUTION_FAILED=-808",
            "ERR_DNS_NAME_HTTPS_ONLY=-809", "ERR_DNS_REQUEST_CANCELLED=-810",
            "ERR_DNS_NO_MATCHING_SUPPORTED_ALPN=-811", "ERR_DNS_SECURE_PROBE_RECORD_INVALID=-814",
            "ERR_DNS_CACHE_INVALIDATION_IN_PROGRESS=-815", "ERR_DNS_FORMAT_ERROR=-816",
            "ERR_DNS_SERVER_FAILURE=-817", "ERR_DNS_NOT_IMPLEMENTED=-818", "ERR_DNS_REFUSED=-819",
            "ERR_DNS_OTHER_FAILURE=-820", "ERR_DNS_DIRECT_ONLY=-821",
            "ERR_BLOB_INVALID_CONSTRUCTION_ARGUMENTS=-900", "ERR_BLOB_OUT_OF_MEMORY=-901",
            "ERR_BLOB_FILE_WRITE_FAILED=-902", "ERR_BLOB_SOURCE_DIED_IN_TRANSIT=-903",
            "ERR_BLOB_DEREFERENCED_WHILE_BUILDING=-904", "ERR_BLOB_REFERENCED_BLOB_BROKEN=-905",
            "ERR_BLOB_REFERENCED_FILE_UNAVAILABLE=-906"};
}
