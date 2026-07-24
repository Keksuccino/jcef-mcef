// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.callback;

import java.util.Map;
import java.util.Vector;

/**
 * Class used to create and/or parse command line arguments. Arguments with
 * '--', '-' and, on Windows, '/' prefixes are considered switches. Switches
 * will always precede any arguments without switch prefixes. Switches can
 * optionally have a value specified using the '=' delimiter (e.g.
 * "-switch=value"). An argument of "--" will terminate switch parsing with all
 * subsequent tokens, regardless of prefix, being interpreted as non-switch
 * arguments. Switch names are considered case-insensitive.
 */
public interface CefCommandLine {
    /**
     * Create a new writable command line. The returned instance owns a native reference and should
     * be released with {@link #dispose()} when it is no longer needed.
     */
    public static CefCommandLine createCommandLine() {
        return CefCommandLine_N.createNative();
    }

    /**
     * Return the read-only global command line. The returned instance owns a native reference and
     * should be released with {@link #dispose()} when it is no longer needed.
     */
    public static CefCommandLine getGlobalCommandLine() {
        return CefCommandLine_N.getGlobalNative();
    }

    /**
     * Release this Java object's native reference. This method is idempotent. Command lines passed
     * to callbacks are callback-scoped and must not be disposed or retained by client code.
     */
    public void dispose();

    /**
     * Returns true if this object is valid. Do not call any other methods if this function returns
     * false.
     */
    public boolean isValid();

    /**
     * Returns true if the values of this object are read-only.
     */
    public boolean isReadOnly();

    /**
     * Returns a writable copy of this object. The returned instance must be released with
     * {@link #dispose()}.
     */
    public CefCommandLine copy();

    /**
     * Initialize from an argv-style array whose first element is the program name. This method is
     * supported on non-Windows platforms only.
     *
     * @param argv non-null argv elements, including the program name at index zero
     * @throws IllegalArgumentException if {@code argv} is empty or its program name is empty
     * @throws NullPointerException if {@code argv} or one of its elements is null
     * @throws UnsupportedOperationException on Windows
     */
    public void initFromArgv(String[] argv);

    /**
     * Initialize from the string returned by the Windows {@code GetCommandLineW()} function. This
     * method is supported on Windows only.
     *
     * @throws NullPointerException if {@code commandLine} is null
     * @throws UnsupportedOperationException on non-Windows platforms
     */
    public void initFromString(String commandLine);

    /**
     * Reset the command-line switches and arguments but leave the program
     * component unchanged.
     */
    public void reset();

    /**
     * Retrieve the represented command line as argv-style values.
     */
    public Vector<String> getArgv();

    /**
     * Construct and return the represented command line string. Quoting behavior is platform
     * dependent.
     */
    public String getCommandLineString();

    /**
     * Get the program part of the command line string (the first item).
     */
    public String getProgram();

    /**
     * Set the program part of the command line string (the first item).
     * @param program Name of the program.
     */
    public void setProgram(String program);

    /**
     * Checks if the command line has switches.
     * @return true if the command line has switches.
     */
    public boolean hasSwitches();

    /**
     * Checks if the command line has a specific switches.
     * @param name A switch name to test for.
     * @return true if the command line contains the given switch.
     */
    public boolean hasSwitch(String name);

    /**
     * Returns the value associated with the given switch. If the switch has no
     * value or isn't present this method returns the empty string.
     * @param name the name of the switch.
     * @return the value of the switch.
     */
    public String getSwitchValue(String name);

    /**
     * Returns the map of switch names and values. If a switch has no value an
     * empty string is returned.
     * @return Map of switches and each value.
     */
    public Map<String, String> getSwitches();

    /**
     * Add a switch with an empty value to the end of the command line.
     * @param name name of the switch.
     */
    public void appendSwitch(String name);

    /**
     * Add a switch with the specified value to the end of the command line.
     * @param name name of the switch.
     * @param value value for the switch.
     */
    public void appendSwitchWithValue(String name, String value);

    /**
     * Remove a switch. This method has no effect if the switch is not present.
     */
    public void removeSwitch(String name);

    /**
     * Tests if there are remaining command line arguments.
     * @return True if there are remaining command line arguments.
     */
    public boolean hasArguments();

    /**
     * Get the remaining command line arguments.
     * @return Vector of command line arguments.
     */
    public Vector<String> getArguments();

    /**
     * Add an argument to the end of the command line.
     * @param argument name of the argument.
     */
    public void appendArgument(String argument);

    /**
     * Insert a command before the current command, as commonly used for debugger wrappers.
     */
    public void prependWrapper(String wrapper);
}
