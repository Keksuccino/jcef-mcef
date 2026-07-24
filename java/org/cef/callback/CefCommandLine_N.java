// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import java.util.Map;
import java.util.Objects;
import java.util.Vector;

class CefCommandLine_N extends CefNativeAdapter implements CefCommandLine {
    CefCommandLine_N() {}

    static CefCommandLine createNative() {
        try {
            return N_Create();
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return null;
    }

    static CefCommandLine getGlobalNative() {
        try {
            return N_GetGlobalCommandLine();
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return null;
    }

    @Override
    protected void finalize() throws Throwable {
        dispose();
        super.finalize();
    }

    @Override
    public void dispose() {
        try {
            N_Dispose(getNativeRef(null));
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
    }

    @Override
    public boolean isValid() {
        try {
            return N_IsValid(getNativeRef(null));
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean isReadOnly() {
        try {
            return N_IsReadOnly(getNativeRef(null));
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return true;
    }

    @Override
    public CefCommandLine copy() {
        try {
            return N_Copy(getNativeRef(null));
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return null;
    }

    @Override
    public void initFromArgv(String[] argv) {
        validateArgv(argv);
        try {
            N_InitFromArgv(getNativeRef(null), argv);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
    }

    @Override
    public void initFromString(String commandLine) {
        Objects.requireNonNull(commandLine, "commandLine");
        try {
            N_InitFromString(getNativeRef(null), commandLine);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
    }

    @Override
    public void reset() {
        try {
            N_Reset(getNativeRef(null));
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
    }

    @Override
    public Vector<String> getArgv() {
        try {
            return N_GetArgv(getNativeRef(null));
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return null;
    }

    @Override
    public String getCommandLineString() {
        try {
            return N_GetCommandLineString(getNativeRef(null));
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return null;
    }

    @Override
    public String getProgram() {
        try {
            return N_GetProgram(getNativeRef(null));
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return null;
    }

    @Override
    public void setProgram(String program) {
        Objects.requireNonNull(program, "program");
        try {
            N_SetProgram(getNativeRef(null), program);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
    }

    @Override
    public boolean hasSwitches() {
        try {
            return N_HasSwitches(getNativeRef(null));
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean hasSwitch(String name) {
        Objects.requireNonNull(name, "name");
        try {
            return N_HasSwitch(getNativeRef(null), name);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return false;
    }

    @Override
    public String getSwitchValue(String name) {
        Objects.requireNonNull(name, "name");
        try {
            return N_GetSwitchValue(getNativeRef(null), name);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return null;
    }

    @Override
    public Map<String, String> getSwitches() {
        try {
            return N_GetSwitches(getNativeRef(null));
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return null;
    }

    @Override
    public void appendSwitch(String name) {
        Objects.requireNonNull(name, "name");
        try {
            N_AppendSwitch(getNativeRef(null), name);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
    }

    @Override
    public void appendSwitchWithValue(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        try {
            N_AppendSwitchWithValue(getNativeRef(null), name, value);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
    }

    @Override
    public void removeSwitch(String name) {
        Objects.requireNonNull(name, "name");
        try {
            N_RemoveSwitch(getNativeRef(null), name);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
    }

    @Override
    public boolean hasArguments() {
        try {
            return N_HasArguments(getNativeRef(null));
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return false;
    }

    @Override
    public Vector<String> getArguments() {
        try {
            return N_GetArguments(getNativeRef(null));
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
        return null;
    }

    @Override
    public void appendArgument(String argument) {
        Objects.requireNonNull(argument, "argument");
        try {
            N_AppendArgument(getNativeRef(null), argument);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
    }

    @Override
    public void prependWrapper(String wrapper) {
        Objects.requireNonNull(wrapper, "wrapper");
        try {
            N_PrependWrapper(getNativeRef(null), wrapper);
        } catch (UnsatisfiedLinkError err) {
            err.printStackTrace();
        }
    }

    @Override
    public String toString() {
        String result = "CefCommandLine [program=\'" + getProgram() + "\'";
        if (hasSwitches()) {
            Map<String, String> switches = getSwitches();
            result += ", switches=" + switches;
        }
        if (hasArguments()) {
            Vector<String> arguments = getArguments();
            result += ", arguments=" + arguments;
        }
        return result + "]";
    }

    private static void validateArgv(String[] argv) {
        Objects.requireNonNull(argv, "argv");
        if (argv.length == 0) {
            throw new IllegalArgumentException("argv must contain the program name");
        }
        for (int i = 0; i < argv.length; i++) {
            Objects.requireNonNull(argv[i], "argv[" + i + "]");
        }
        if (argv[0].isEmpty()) {
            throw new IllegalArgumentException("argv[0] must contain the program name");
        }
    }

    private static native CefCommandLine_N N_Create();
    private static native CefCommandLine_N N_GetGlobalCommandLine();
    private native void N_Dispose(long self);
    private native boolean N_IsValid(long self);
    private native boolean N_IsReadOnly(long self);
    private native CefCommandLine_N N_Copy(long self);
    private native void N_InitFromArgv(long self, String[] argv);
    private native void N_InitFromString(long self, String commandLine);
    private native void N_Reset(long self);
    private native Vector<String> N_GetArgv(long self);
    private native String N_GetCommandLineString(long self);
    private native String N_GetProgram(long self);
    private native void N_SetProgram(long self, String program);
    private native boolean N_HasSwitches(long self);
    private native boolean N_HasSwitch(long self, String name);
    private native String N_GetSwitchValue(long self, String name);
    private native Map<String, String> N_GetSwitches(long self);
    private native void N_AppendSwitch(long self, String name);
    private native void N_AppendSwitchWithValue(long self, String name, String value);
    private native void N_RemoveSwitch(long self, String name);
    private native boolean N_HasArguments(long self);
    private native Vector<String> N_GetArguments(long self);
    private native void N_AppendArgument(long self, String argument);
    private native void N_PrependWrapper(long self, String wrapper);
}
