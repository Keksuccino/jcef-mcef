// Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#import "util_mac.h"
#import "util.h"

#import <Cocoa/Cocoa.h>
#import <Foundation/NSLock.h>
#import <jni.h>
#include <objc/runtime.h>
#include <atomic>

#include "include/base/cef_callback.h"
#include "include/cef_app.h"
#include "include/cef_application_mac.h"
#include "include/cef_browser.h"
#include "include/cef_path_util.h"

#include "client_app.h"
#include "client_handler.h"
#include "context.h"
#include "critical_wait.h"
#include "jni_util.h"
#include "render_handler.h"
#include "temp_window.h"
#include "window_handler.h"

namespace {

static std::set<CefWindowHandle> g_browsers_;
static CriticalLock g_browsers_lock_;
id g_mouse_monitor_ = nil;
static CefRefPtr<ClientApp> g_client_app_ = nullptr;
bool g_handling_send_event = false;

enum MessageLoopWorkState {
  MESSAGE_LOOP_WORK_IDLE,
  MESSAGE_LOOP_WORK_QUEUED,
  MESSAGE_LOOP_WORK_RUNNING,
  MESSAGE_LOOP_WORK_RERUN,
};
std::atomic<int> g_message_loop_work_state_(MESSAGE_LOOP_WORK_IDLE);

}  // namespace

// Used for passing data to/from ClientHandler initialize:.
@interface InitializeParams : NSObject {
 @public
  CefMainArgs args_;
  CefSettings settings_;
  CefRefPtr<ClientApp> application_;
  bool result_;
}
@end
@implementation InitializeParams
@end

// Used for passing data to/from ClientHandler setVisiblity:.
@interface SetVisibilityParams : NSObject {
 @public
  CefWindowHandle handle_;
  bool isVisible_;
}
@end
@implementation SetVisibilityParams
@end

// Used for passing data to/from CefHandler updateView: without dereferencing
// the unowned CEF NSView handle on the Java callback thread.
@interface UpdateViewParams : NSObject {
 @public
  CefWindowHandle handle_;
  CefRect content_rect_;
  CefRect browser_rect_;
}
@end
@implementation UpdateViewParams
@end

// Obj-C Wrapper Class to be called by "performSelectorOnMainThread".
@interface CefHandler : NSObject {
}

+ (void)initialize:(InitializeParams*)params;
+ (void)shutdown;
+ (void)doMessageLoopWork;
+ (void)setVisibility:(SetVisibilityParams*)params;
+ (void)updateView:(UpdateViewParams*)params;

@end  // interface CefHandler

namespace {

NSView* RetainRegisteredBrowserViewOnMainThread(CefWindowHandle handle) {
  DCHECK([NSThread isMainThread]);
  g_browsers_lock_.Lock();
  NSView* view = g_browsers_.count(handle) > 0
                     ? [CAST_CEF_WINDOW_HANDLE_TO_NSVIEW(handle) retain]
                     : nil;
  g_browsers_lock_.Unlock();
  return view;
}

void UpdateViewOnMainThread(UpdateViewParams* params);

void InstallMouseMonitorOnMainThread() {
  DCHECK([NSThread isMainThread]);
  if (g_mouse_monitor_)
    return;

  g_mouse_monitor_ = [NSEvent
      addLocalMonitorForEventsMatchingMask:(NSEventMaskLeftMouseDown |
                                            NSEventMaskLeftMouseUp |
                                            NSEventMaskLeftMouseDragged |
                                            NSEventMaskRightMouseDown |
                                            NSEventMaskRightMouseUp |
                                            NSEventMaskRightMouseDragged |
                                            NSEventMaskOtherMouseDown |
                                            NSEventMaskOtherMouseUp |
                                            NSEventMaskOtherMouseDragged |
                                            NSEventMaskScrollWheel |
                                            NSEventMaskMouseMoved |
                                            NSEventMaskMouseEntered |
                                            NSEventMaskMouseExited)
                                   handler:^(NSEvent* evt) {
                                     // Get corresponding CefWindowHandle of
                                     // Java-Canvas
                                     NSView* browser = nullptr;
                                     NSPoint absPos = [evt locationInWindow];
                                     NSWindow* evtWin = [evt window];
                                     g_browsers_lock_.Lock();
                                     std::set<CefWindowHandle> browsers =
                                         g_browsers_;
                                     g_browsers_lock_.Unlock();

                                     std::set<CefWindowHandle>::iterator it;
                                     for (it = browsers.begin();
                                          it != browsers.end(); ++it) {
                                       NSView* wh =
                                           CAST_CEF_WINDOW_HANDLE_TO_NSVIEW(
                                               *it);
                                       NSPoint relPos = [wh convertPoint:absPos
                                                                fromView:nil];
                                       if (evtWin == [wh window] &&
                                           [wh mouse:relPos
                                               inRect:[wh frame]]) {
                                         browser = wh;
                                         break;
                                       }
                                     }

                                     if (!browser)
                                       return evt;

                                     // Forward mouse event to browsers parent
                                     // (JCEF UI)
                                     switch ([evt type]) {
                                       case NSEventTypeLeftMouseDown:
                                       case NSEventTypeOtherMouseDown:
                                       case NSEventTypeRightMouseDown:
                                         [[browser superview] mouseDown:evt];
                                         return evt;

                                       case NSEventTypeLeftMouseUp:
                                       case NSEventTypeOtherMouseUp:
                                       case NSEventTypeRightMouseUp:
                                         [[browser superview] mouseUp:evt];
                                         return evt;

                                       case NSEventTypeLeftMouseDragged:
                                       case NSEventTypeOtherMouseDragged:
                                       case NSEventTypeRightMouseDragged:
                                         [[browser superview] mouseDragged:evt];
                                         return evt;

                                       case NSEventTypeMouseMoved:
                                         [[browser superview] mouseMoved:evt];
                                         return evt;

                                       case NSEventTypeMouseEntered:
                                         [[browser superview] mouseEntered:evt];
                                         return evt;

                                       case NSEventTypeMouseExited:
                                         [[browser superview] mouseExited:evt];
                                         return evt;

                                       case NSEventTypeScrollWheel:
                                         [[browser superview] scrollWheel:evt];
                                         return evt;

                                       default:
                                         return evt;
                                     }
                                   }];
}

void RemoveMouseMonitorOnMainThread() {
  DCHECK([NSThread isMainThread]);
  if (!g_mouse_monitor_)
    return;

  [NSEvent removeMonitor:g_mouse_monitor_];
  g_mouse_monitor_ = nil;
}

}  // namespace

// Java provides an NSApplicationAWT implementation that we can't access or
// override directly. Therefore add the necessary CefAppProtocol
// functionality to NSApplication using categories and swizzling.
@interface NSApplication (JCEFApplication) <CefAppProtocol>

- (BOOL)isHandlingSendEvent;
- (void)setHandlingSendEvent:(BOOL)handlingSendEvent;
- (void)_swizzled_sendEvent:(NSEvent*)event;
- (void)_swizzled_terminate:(id)sender;

@end

@implementation NSApplication (JCEFApplication)

// This selector is called very early during the application initialization.
+ (void)load {
  // Swap NSApplication::sendEvent with _swizzled_sendEvent.
  Method original = class_getInstanceMethod(self, @selector(sendEvent));
  Method swizzled =
      class_getInstanceMethod(self, @selector(_swizzled_sendEvent));
  method_exchangeImplementations(original, swizzled);

  Method originalTerm = class_getInstanceMethod(self, @selector(terminate:));
  Method swizzledTerm =
      class_getInstanceMethod(self, @selector(_swizzled_terminate:));
  method_exchangeImplementations(originalTerm, swizzledTerm);
}

- (BOOL)isHandlingSendEvent {
  return g_handling_send_event;
}

- (void)setHandlingSendEvent:(BOOL)handlingSendEvent {
  g_handling_send_event = handlingSendEvent;
}

- (void)_swizzled_sendEvent:(NSEvent*)event {
  CefScopedSendingEvent sendingEventScoper;
  // Calls NSApplication::sendEvent due to the swizzling.
  [self _swizzled_sendEvent:event];
}

// This method will be called via Cmd+Q.
- (void)_swizzled_terminate:(id)sender {
  bool continueTerminate = true;

  if (g_client_app_.get()) {
    // Call CefApp.handleBeforeTerminate() in Java. Will result in a call
    // to CefShutdownOnMainThread() via CefApp.shutdown().
    continueTerminate = !g_client_app_->HandleTerminate();
  }

  if (continueTerminate)
    [[CefHandler class] shutdown];
}

@end

@implementation CefHandler

// |params| will be released by the caller.
+ (void)initialize:(InitializeParams*)params {
  DCHECK([NSThread isMainThread]);
  // Installing an NSEvent monitor during this image's +load can initialize
  // NSApplication on the Java launcher thread before AWT owns AppKit. Defer
  // all AppKit messaging until AWT has created its main-thread application.
  InstallMouseMonitorOnMainThread();
  g_client_app_ = params->application_;
  params->result_ = CefInitialize(params->args_, params->settings_,
                                  g_client_app_.get(), nullptr);
  if (!params->result_) {
    g_client_app_ = nullptr;
    RemoveMouseMonitorOnMainThread();
  }
}

+ (void)shutdown {
  DCHECK([NSThread isMainThread]);
  // Cmd+Q and Java disposal can converge here. Only the first path owns
  // CefShutdown.
  if (!g_client_app_)
    return;

  // Pump CefDoMessageLoopWork a few times before shutting down.
  for (int i = 0; i < 10; ++i)
    CefDoMessageLoopWork();

  // TempWindowMac owns an NSWindow and therefore must be destroyed on the
  // AppKit main thread. Context is deleted later on the Java lifecycle thread
  // after this synchronous selector returns.
  Context* context = Context::GetInstance();
  if (context)
    context->DestroyTempWindowOnMainThread();

  CefShutdown();
  g_client_app_ = nullptr;

  RemoveMouseMonitorOnMainThread();
}

+ (void)doMessageLoopWork {
  DCHECK([NSThread isMainThread]);
  int expected = MESSAGE_LOOP_WORK_QUEUED;
  bool transitioned = g_message_loop_work_state_.compare_exchange_strong(
      expected, MESSAGE_LOOP_WORK_RUNNING, std::memory_order_acq_rel);
  DCHECK(transitioned);
  if (!transitioned)
    return;

  for (;;) {
    if (g_client_app_)
      CefDoMessageLoopWork();

    expected = MESSAGE_LOOP_WORK_RUNNING;
    if (g_message_loop_work_state_.compare_exchange_strong(
            expected, MESSAGE_LOOP_WORK_IDLE, std::memory_order_acq_rel)) {
      break;
    }

    // A request that arrived while CefDoMessageLoopWork was running must not be
    // lost. Consume exactly one additional iteration; further concurrent calls
    // are folded into the same RERUN state.
    DCHECK_EQ(expected, MESSAGE_LOOP_WORK_RERUN);
    g_message_loop_work_state_.store(MESSAGE_LOOP_WORK_RUNNING,
                                     std::memory_order_release);
  }
}

+ (void)setVisibility:(SetVisibilityParams*)params {
  DCHECK([NSThread isMainThread]);

  // The Java AWT hierarchy callback and CEF browser destruction are
  // asynchronous with respect to one another. Resolve and retain the raw CEF
  // window handle under the registry lock on AppKit immediately before use.
  NSView* view = RetainRegisteredBrowserViewOnMainThread(params->handle_);
  if (g_client_app_ && view) {
    bool isHidden = [view isHidden];
    if (isHidden == params->isVisible_) {
      [view setHidden:!params->isVisible_];
      [view setNeedsDisplay:YES];
      [[view superview] setNeedsDisplay:YES];
    }
  }
  [view release];
  [params release];
}

+ (void)updateView:(UpdateViewParams*)params {
  UpdateViewOnMainThread(params);
}

@end  // implementation CefHandler

// Instead of adding CefBrowser as child of the windows main view, a content
// view (CefBrowserContentView) is set between the main view and the
// CefBrowser. Why?
//
// The CefBrowserContentView defines the viewable part of the browser view.
// In most cases the content view has the same size as the browser view,
// but for example if you add CefBrowser to a JScrollPane, you only want to
// see the portion of the browser window you're scrolled to. In this case
// the sizes of the content view and the browser view differ.
//
// With other words the CefBrowserContentView clips the CefBrowser to its
// displayable content.
//
// +- - - - - - - - - - - - - - - - - - - - -+
// |/   / CefBrowser/   /   /   /   /   /   /|
//    +-------------------------+  /   /   / <--- invisible part of CefBrowser
// |  | CefBrowserContentView   | /   /   /  |
//   /|                         |/   /   /
// |/ |                         |   /   /   /|
//    |                       <------------------ visible part of CefBrowser
// |  |                         | /   /   /  |
//   /|                         |/   /   /
// |/ |                         |   /   /   /|
//    |                         |  /   /   /
// |  +-------------------------+ /   /   /  |
//   /   /   /   /   /   /   /   /   /   /
// |/   /   /   /   /   /   /   /   /   /   /|
//     /   /   /   /   /   /   /   /   /   /
// |  /   /   /   /   /   /   /   /   /   /  |
// +- - - - - - - - - - - - - - - - - - - - -+
@interface CefBrowserContentView : NSView {
  CefRefPtr<CefBrowser> cefBrowser;
}

@property(readonly) BOOL isLiveResizing;

- (void)addCefBrowser:(CefRefPtr<CefBrowser>)browser;
- (void)destroyCefBrowser;
- (void)updateView:(NSDictionary*)dict;
@end  // interface CefBrowserContentView

@implementation CefBrowserContentView

@synthesize isLiveResizing;

- (id)initWithFrame:(NSRect)frameRect {
  self = [super initWithFrame:frameRect];
  cefBrowser = nullptr;
  return self;
}

- (void)dealloc {
  if (cefBrowser) {
    util::DestroyCefBrowser(cefBrowser);
  }
  [[NSNotificationCenter defaultCenter] removeObserver:self];
  cefBrowser = nullptr;
  [super dealloc];
}

- (void)setFrame:(NSRect)frameRect {
  // Instead of using the passed frame, get the visible rect from java because
  // the passed frame rect doesn't contain the clipped view part. Otherwise
  // we'll overlay some parts of the Java UI.
  if (cefBrowser.get()) {
    CefRefPtr<ClientHandler> clientHandler =
        (ClientHandler*)(cefBrowser->GetHost()->GetClient().get());

    CefRefPtr<WindowHandler> windowHandler = clientHandler->GetWindowHandler();
    if (windowHandler.get() != nullptr) {
      CefRect rect;
      bool got_rect = windowHandler->GetRect(cefBrowser, rect);
      if (got_rect) {
        util_mac::TranslateRect(self, rect);
        frameRect = (NSRect){{rect.x, rect.y}, {rect.width, rect.height}};
      }
    }
  }
  [super setFrame:frameRect];
}

- (void)addCefBrowser:(CefRefPtr<CefBrowser>)browser {
  cefBrowser = browser;
  // Register for the start and end events of "liveResize" to avoid
  // Java paint updates while someone is resizing the main window (e.g. by
  // pulling with the mouse cursor)
  [[NSNotificationCenter defaultCenter]
      addObserver:self
         selector:@selector(windowWillStartLiveResize:)
             name:NSWindowWillStartLiveResizeNotification
           object:[self window]];
  [[NSNotificationCenter defaultCenter]
      addObserver:self
         selector:@selector(windowDidEndLiveResize:)
             name:NSWindowDidEndLiveResizeNotification
           object:[self window]];
}

- (void)destroyCefBrowser {
  [[NSNotificationCenter defaultCenter] removeObserver:self];
  cefBrowser = nullptr;
  [self removeFromSuperview];
  // Also remove all subviews so the CEF objects are released.
  for (NSView* view in [self subviews]) {
    [view removeFromSuperview];
  }
}

- (void)windowWillStartLiveResize:(NSNotification*)notification {
  isLiveResizing = YES;
}

- (void)windowDidEndLiveResize:(NSNotification*)notification {
  isLiveResizing = NO;
  [self setFrame:[self frame]];
}

- (void)updateView:(NSDictionary*)dict {
  NSRect contentRect = NSRectFromString([dict valueForKey:@"content"]);
  NSRect browserRect = NSRectFromString([dict valueForKey:@"browser"]);

  NSArray* childs = [self subviews];
  for (NSView* child in childs) {
    [child setFrame:browserRect];
    [child setNeedsDisplay:YES];
  }
  [super setFrame:contentRect];
  [self setNeedsDisplay:YES];
}

@end  // implementation CefBrowserContentView

namespace {

void UpdateViewOnMainThread(UpdateViewParams* params) {
  DCHECK([NSThread isMainThread]);

  NSView* view = RetainRegisteredBrowserViewOnMainThread(params->handle_);
  if (!view) {
    [params release];
    return;
  }

  CefRect content_rect = params->content_rect_;
  CefRect browser_rect = params->browser_rect_;
  util_mac::TranslateRect(view, content_rect);
  browser_rect.y = content_rect.height - browser_rect.height - browser_rect.y;
  CefBrowserContentView* browser =
      (CefBrowserContentView*)[[view superview] retain];
  [view release];

  // Only update the view if nobody is currently resizing the main window.
  // Otherwise the CEF view may flicker due to the delay between the native
  // window resize event and the Java resize event.
  if ([browser isKindOfClass:[CefBrowserContentView class]] &&
      ![browser isLiveResizing]) {
    NSString* content_string = [NSString
        stringWithFormat:@"{{%d,%d},{%d,%d}", content_rect.x, content_rect.y,
                         content_rect.width, content_rect.height];
    NSString* browser_string = [NSString
        stringWithFormat:@"{{%d,%d},{%d,%d}", browser_rect.x, browser_rect.y,
                         browser_rect.width, browser_rect.height];
    NSDictionary* rects = [NSDictionary
        dictionaryWithObjectsAndKeys:content_string, @"content", browser_string,
                                     @"browser", nil];
    [browser updateView:rects];
  }
  [browser release];
  [params release];
}

}  // namespace

namespace util_mac {

std::string GetAbsPath(const std::string& path) {
  char full_path[PATH_MAX];
  if (realpath(path.c_str(), full_path) == nullptr)
    return std::string();
  return full_path;
}

bool IsNSView(void* ptr) {
  id obj = (id)ptr;
  bool result = [obj isKindOfClass:[NSView class]];
  if (!result)
    NSLog(@"Expected NSView, found %@", NSStringFromClass([obj class]));
  return result;
}

CefWindowHandle CreateBrowserContentView(NSWindow* window, CefRect& orig) {
  NSView* mainView = CAST_CEF_WINDOW_HANDLE_TO_NSVIEW([window contentView]);
  TranslateRect(mainView, orig);
  NSRect frame = {{orig.x, orig.y}, {orig.width, orig.height}};

  CefBrowserContentView* contentView =
      [[CefBrowserContentView alloc] initWithFrame:frame];

  // Make the content view for the window have a layer. This will make all
  // sub-views have layers. This is necessary to ensure correct layer
  // ordering of all child views and their layers.
  [contentView setWantsLayer:YES];

  [mainView addSubview:contentView];
  [contentView setAutoresizingMask:(NSViewWidthSizable | NSViewHeightSizable)];
  [contentView setNeedsDisplay:YES];

  [contentView release];

  // Override origin before "orig" is returned because the new origin is
  // relative to the created CefBrowserContentView object
  orig.x = 0;
  orig.y = 0;
  return contentView;
}

// translate java's window origin to Obj-C's window origin
void TranslateRect(CefWindowHandle view, CefRect& orig) {
  NSRect bounds =
      [[[CAST_CEF_WINDOW_HANDLE_TO_NSVIEW(view) window] contentView] bounds];
  orig.y = bounds.size.height - orig.height - orig.y;
}

bool CefInitializeOnMainThread(const CefMainArgs& args,
                               const CefSettings& settings,
                               CefRefPtr<ClientApp> application) {
  InitializeParams* params = [[InitializeParams alloc] init];
  params->args_ = args;
  params->settings_ = settings;
  params->application_ = application;
  params->result_ = false;

  // Block until done.
  [[CefHandler class] performSelectorOnMainThread:@selector(initialize:)
                                       withObject:params
                                    waitUntilDone:YES];
  int result = params->result_;
  [params release];
  return result;
}

void CefShutdownOnMainThread() {
  // Block until done.
  [[CefHandler class] performSelectorOnMainThread:@selector(shutdown)
                                       withObject:nil
                                    waitUntilDone:YES];
}

void CefDoMessageLoopWorkOnMainThread() {
  int state = g_message_loop_work_state_.load(std::memory_order_acquire);
  for (;;) {
    if (state == MESSAGE_LOOP_WORK_QUEUED || state == MESSAGE_LOOP_WORK_RERUN) {
      return;
    }
    int requested_state = state == MESSAGE_LOOP_WORK_IDLE
                              ? MESSAGE_LOOP_WORK_QUEUED
                              : MESSAGE_LOOP_WORK_RERUN;
    if (g_message_loop_work_state_.compare_exchange_weak(
            state, requested_state, std::memory_order_acq_rel)) {
      if (requested_state == MESSAGE_LOOP_WORK_RERUN)
        return;
      break;
    }
  }

  [[CefHandler class]
      performSelector:@selector(doMessageLoopWork)
             onThread:[NSThread mainThread]
           withObject:nil
        waitUntilDone:NO
                modes:@[ NSDefaultRunLoopMode, NSEventTrackingRunLoopMode ]];
}

void SetVisibility(CefWindowHandle handle, bool isVisible) {
  SetVisibilityParams* params = [[SetVisibilityParams alloc] init];
  params->handle_ = handle;
  params->isVisible_ = isVisible;
  [[CefHandler class] performSelectorOnMainThread:@selector(setVisibility:)
                                       withObject:params
                                    waitUntilDone:NO];
}

void UpdateView(CefWindowHandle handle,
                CefRect contentRect,
                CefRect browserRect) {
  UpdateViewParams* params = [[UpdateViewParams alloc] init];
  params->handle_ = handle;
  params->content_rect_ = contentRect;
  params->browser_rect_ = browserRect;
  [[CefHandler class] performSelectorOnMainThread:@selector(updateView:)
                                       withObject:params
                                    waitUntilDone:NO];
}

}  // namespace util_mac

namespace util {

void AddCefBrowser(CefRefPtr<CefBrowser> browser) {
  if (!browser.get())
    return;
  CefWindowHandle handle = browser->GetHost()->GetWindowHandle();
  g_browsers_lock_.Lock();
  g_browsers_.insert(handle);
  g_browsers_lock_.Unlock();
  CefBrowserContentView* browserImpl =
      (CefBrowserContentView*)[CAST_CEF_WINDOW_HANDLE_TO_NSVIEW(handle)
          superview];
  [browserImpl addCefBrowser:browser];
}

void DestroyCefBrowser(CefRefPtr<CefBrowser> browser) {
  if (!browser.get())
    return;
  NSView* handle =
      CAST_CEF_WINDOW_HANDLE_TO_NSVIEW(browser->GetHost()->GetWindowHandle());
  g_browsers_lock_.Lock();
  bool browser_exists = g_browsers_.erase(handle) > 0;
  g_browsers_lock_.Unlock();
  if (!browser_exists)
    return;

  // There are some cases where the superview of CefBrowser isn't
  // a CefBrowserContentView. For example if another CefBrowser window was
  // created by calling "window.open()" in JavaScript.
  NSView* superView = [handle superview];
  if ([superView isKindOfClass:[CefBrowserContentView class]]) {
    CefBrowserContentView* browserView =
        (CefBrowserContentView*)[handle superview];
    [browserView destroyCefBrowser];
  }
}

void SetParent(CefWindowHandle handle, jlong parentHandle, base::OnceClosure callback) {
  // CefBrowserWindowMac keeps the AWT CPlatformWindow alive only through this
  // JNI call. Retain the NSWindow before returning to Java, then carry it as an
  // integer so block capture cannot add hidden Objective-C ownership.
  NSWindow* parent_window = parentHandle ? [(NSWindow*)parentHandle retain] : nil;
  jlong retained_parent_handle = (jlong)parent_window;
  base::RepeatingClosure* pCallback = new base::RepeatingClosure(base::BindRepeating([](base::OnceClosure& cb) { std::move(cb).Run(); }, OwnedRef(std::move(callback))));
  dispatch_async(dispatch_get_main_queue(), ^{
    NSView* view = RetainRegisteredBrowserViewOnMainThread(handle);
    if (!view) {
      // Browser destruction won the race, so there is no parent change to
      // report. Destroying the closure also releases its CefBrowser reference;
      // invoking it here would emit a lifecycle callback after native close.
      [(NSWindow*)retained_parent_handle release];
      delete pCallback;
      return;
    }

    CefBrowserContentView* browser_view = (CefBrowserContentView*)[[view superview] retain];
    [view release];
    [browser_view removeFromSuperview];

    NSView* contentView;
    if (retained_parent_handle) {
      NSWindow* window = (NSWindow*)retained_parent_handle;
      contentView = [window contentView];
    } else {
      contentView = CAST_CEF_WINDOW_HANDLE_TO_NSVIEW(TempWindow::GetWindowHandle());
    }
    [contentView addSubview:browser_view];
    [(NSWindow*)retained_parent_handle release];
    [browser_view release];
    pCallback->Run();
    delete pCallback;
  });
}

}  // namespace util
