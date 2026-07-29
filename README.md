# JCEF for Rinku

This is a modified version of JCEF for use with the "Rinku" Minecraft mod. It preserves Rinku's headless, externally driven off-screen rendering integration while tracking the upstream JCEF APIs and native implementation.

This revision is pinned to CEF
`151.2.3+g89cd581+chromium-151.0.7922.34` with stable API version `15100` and
targets Java 17 exactly. Its supported publication targets are
`linux_amd64`, `linux_arm64`, `macos_amd64`, `macos_arm64`,
`windows_amd64`, and `windows_arm64`.

The Java Chromium Embedded Framework (JCEF) is a simple framework for embedding Chromium-based browsers in other applications using the Java programming language.

# Introduction

CEF is a BSD-licensed open source project founded by Marshall Greenblatt in 2008 and based on the [Google Chromium](http://www.chromium.org/Home) project. Unlike the Chromium project itself, which focuses mainly on Google Chrome application development, CEF focuses on facilitating embedded browser use cases in third-party applications. CEF insulates the user from the underlying Chromium and Blink code complexity by offering production-quality stable APIs, release branches tracking specific Chromium releases, and binary distributions. Most features in CEF have default implementations that provide rich functionality while requiring little or no integration work from the user. There are currently over 100 million installed instances of CEF around the world embedded in products from a wide range of companies and industries. A partial list of companies and products using CEF is available on the [CEF Wikipedia page](http://en.wikipedia.org/wiki/Chromium_Embedded_Framework#Applications_using_CEF). Some use cases for CEF include:

* Embedding an HTML5-compliant Web browser control in an existing native application.
* Creating a light-weight native “shell” application that hosts a user interface developed primarily using Web technologies.
* Rendering Web content “off-screen” in applications that have their own custom drawing frameworks.
* Acting as a host for automated testing of existing Web properties and applications.

CEF supports a wide range of programming languages and operating systems and can be easily integrated into both new and existing applications. It was designed from the ground up with both performance and ease of use in mind. The base framework includes C and C++ programming interfaces exposed via native libraries that insulate the host application from Chromium and Blink implementation details. It provides close integration between the browser and the host application including support for custom plugins, protocols, JavaScript objects and JavaScript extensions. The host application can optionally control resource loading, navigation, context menus, printing and more, while taking advantage of the same performance and HTML5 technologies available in the Google Chrome Web browser.

This project provides a Java Wrapper for CEF (JCEF).