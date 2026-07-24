// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandler;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class CefDisplayHandlerApiTest {
    @Test
    void faviconCallbackUsesSourceCompatibleListContract() throws NoSuchMethodException {
        Method method = CefDisplayHandler.class.getMethod("onFaviconURLChange", CefBrowser.class, List.class);

        assertEquals(void.class, method.getReturnType());
        assertTrue(method.isDefault());
        assertFalse(Modifier.isAbstract(method.getModifiers()));
        Type iconUrlsType = method.getGenericParameterTypes()[1];
        assertTrue(iconUrlsType instanceof ParameterizedType);
        ParameterizedType parameterizedIconUrlsType = (ParameterizedType) iconUrlsType;
        assertEquals(List.class, parameterizedIconUrlsType.getRawType());
        assertEquals(1, parameterizedIconUrlsType.getActualTypeArguments().length);
        assertEquals(String.class, parameterizedIconUrlsType.getActualTypeArguments()[0]);
    }

    @Test
    void adapterAndClientDeclareMatchingFaviconCallbacks() throws NoSuchMethodException {
        Method adapterMethod = CefDisplayHandlerAdapter.class.getDeclaredMethod("onFaviconURLChange", CefBrowser.class, List.class);
        Method clientMethod = CefClient.class.getDeclaredMethod("onFaviconURLChange", CefBrowser.class, List.class);

        assertEquals(void.class, adapterMethod.getReturnType());
        assertFalse(Modifier.isAbstract(adapterMethod.getModifiers()));
        assertEquals(void.class, clientMethod.getReturnType());
        assertFalse(Modifier.isAbstract(clientMethod.getModifiers()));
    }

    @Test
    void nativeBridgeUsesOrderedVectorSnapshotAndExactListDescriptor() throws IOException {
        Path sourcePath = Path.of(System.getProperty("user.dir"), "native", "display_handler.cpp");
        assertTrue(Files.isRegularFile(sourcePath), "Run source contract tests from the repository root");
        String source = Files.readString(sourcePath).replace("\r\n", "\n").replace('\r', '\n');
        int start = source.indexOf("void DisplayHandler::OnFaviconURLChange(");
        int end = source.indexOf("void DisplayHandler::OnFullscreenModeChange(", start);
        assertTrue(start >= 0, "Missing native favicon callback");
        assertTrue(end > start, "Missing callback boundary after native favicon callback");
        String bridge = source.substring(start, end);

        int browserConversion = bridge.indexOf("ScopedJNIBrowser jbrowser(env, browser);");
        int urlConversion = bridge.indexOf("ScopedJNIObjectLocal jicon_urls(env, NewJNIStringVector(env, icon_urls));");
        int nullGuard = bridge.indexOf("if (!jicon_urls)", urlConversion);
        int invocation = bridge.indexOf("JNI_CALL_VOID_METHOD(env, handle_, \"onFaviconURLChange\", \"(Lorg/cef/browser/CefBrowser;Ljava/util/List;)V\", jbrowser.get(), jicon_urls.get());");
        assertTrue(browserConversion >= 0);
        assertTrue(urlConversion > browserConversion);
        assertTrue(nullGuard > urlConversion);
        assertTrue(invocation > nullGuard);
    }
}
