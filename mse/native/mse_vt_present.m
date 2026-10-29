#ifdef __APPLE__
#import "mse_vt_present.h"
#import "mse_ffmpeg.h"
#import "vpresent_impl.h"
#import <jni.h>
#import <jawt.h>
#import <jawt_md.h>
#import <AppKit/AppKit.h>
#import <AVFoundation/AVFoundation.h>
#import <QuartzCore/QuartzCore.h>
#import <dlfcn.h>
#import <math.h>
#import <pthread.h>
#import <stdlib.h>
#import <string.h>

/* Overlay sits on AWTView and takes the hit. Forward mouse-up to Java
   (FileAvNative.onOverlayMouse) so the desktop shell can toggle chrome.
   Do not copy events into Compose — that froze AppKit. */
static JavaVM *mse_jvm;
static jclass mse_native_cls;
static jmethodID mse_mouse_mid;
static jmethodID mse_fs_mid;
static id mse_fs_enter_obs;
static id mse_fs_exit_obs;
static id mse_fs_resize_obs;
static id mse_fs_move_obs;
static unsigned mse_fs_gen;

static void mse_bind_mouse_jni(JNIEnv *env)
{
    jclass local;
    if (!env)
        return;
    if (!mse_jvm)
        (*env)->GetJavaVM(env, &mse_jvm);
    if (!mse_native_cls) {
        local = (*env)->FindClass(env, "io/ymusic/mse/FileAvNative");
        if (!local) {
            if ((*env)->ExceptionCheck(env))
                (*env)->ExceptionClear(env);
            mse_critf(MSE_LOG_WARN, "mse-v mouse FindClass FileAvNative failed");
            return;
        }
        mse_native_cls = (*env)->NewGlobalRef(env, local);
        (*env)->DeleteLocalRef(env, local);
    }
    if (!mse_mouse_mid) {
        mse_mouse_mid = (*env)->GetStaticMethodID(env, mse_native_cls, "onOverlayMouse", "(IIIIIII)V");
        if (!mse_mouse_mid && (*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
    }
    if (!mse_fs_mid) {
        mse_fs_mid = (*env)->GetStaticMethodID(env, mse_native_cls, "onFullScreenChanged", "(Z)V");
        if (!mse_fs_mid && (*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
    }
}

static jint mse_awt_mods(NSEvent *e)
{
    jint m = 0;
    NSUInteger b = NSEvent.pressedMouseButtons;
    if (e.modifierFlags & NSEventModifierFlagShift)
        m |= 64;
    if (e.modifierFlags & NSEventModifierFlagControl)
        m |= 128;
    if (e.modifierFlags & NSEventModifierFlagCommand)
        m |= 256;
    if (e.modifierFlags & NSEventModifierFlagOption)
        m |= 512;
    if (b & 1)
        m |= 1024;
    if (b & 4)
        m |= 2048;
    if (b & 2)
        m |= 4096;
    return m;
}

static jint mse_awt_id(NSEventType t)
{
    switch (t) {
    case NSEventTypeLeftMouseDown:
    case NSEventTypeRightMouseDown:
    case NSEventTypeOtherMouseDown:
        return 501;
    case NSEventTypeLeftMouseUp:
    case NSEventTypeRightMouseUp:
    case NSEventTypeOtherMouseUp:
        return 502;
    case NSEventTypeMouseMoved:
        return 503;
    case NSEventTypeMouseEntered:
        return 504;
    case NSEventTypeMouseExited:
        return 505;
    case NSEventTypeLeftMouseDragged:
    case NSEventTypeRightMouseDragged:
    case NSEventTypeOtherMouseDragged:
        return 506;
    case NSEventTypeScrollWheel:
        return 507;
    default:
        return 503;
    }
}

static jint mse_awt_button(NSEventType t)
{
    switch (t) {
    case NSEventTypeLeftMouseDown:
    case NSEventTypeLeftMouseUp:
    case NSEventTypeLeftMouseDragged:
        return 1;
    case NSEventTypeOtherMouseDown:
    case NSEventTypeOtherMouseUp:
    case NSEventTypeOtherMouseDragged:
        return 2;
    case NSEventTypeRightMouseDown:
    case NSEventTypeRightMouseUp:
    case NSEventTypeRightMouseDragged:
        return 3;
    default:
        return 0;
    }
}

static void mse_java_mouse(NSEvent *e)
{
    NSPoint scr;
    CGFloat top;
    jint x, y, wheel, clicks, eid, mods, button;

    if (!e || !e.window || !mse_jvm || !mse_native_cls || !mse_mouse_mid)
        return;
    scr = [e.window convertPointToScreen:e.locationInWindow];
    top = e.window.screen ? NSMaxY(e.window.screen.frame) : NSMaxY(NSScreen.mainScreen.frame);
    x = (jint)llround(scr.x);
    y = (jint)llround(top - scr.y);
    wheel = (e.type == NSEventTypeScrollWheel) ? (jint)llround(-e.scrollingDeltaY) : 0;
    switch (e.type) {
    case NSEventTypeLeftMouseDown:
    case NSEventTypeLeftMouseUp:
    case NSEventTypeRightMouseDown:
    case NSEventTypeRightMouseUp:
    case NSEventTypeOtherMouseDown:
    case NSEventTypeOtherMouseUp:
        clicks = (jint)e.clickCount;
        break;
    default:
        clicks = 0;
        break;
    }
    eid = mse_awt_id(e.type);
    mods = mse_awt_mods(e);
    button = mse_awt_button(e.type);
    /* Return from NSView mouseDown before Java. AWT EDT is AppKit main:
       dispatchEvent/toFront from this stack freezes the UI. */
    dispatch_async(dispatch_get_main_queue(), ^{
        JNIEnv *async_env = NULL;
        if ((*mse_jvm)->GetEnv(mse_jvm, (void **)&async_env, JNI_VERSION_1_6) != JNI_OK)
            (*mse_jvm)->AttachCurrentThread(mse_jvm, (void **)&async_env, NULL);
        if (!async_env || !mse_native_cls || !mse_mouse_mid)
            return;
        (*async_env)->CallStaticVoidMethod(async_env, mse_native_cls, mse_mouse_mid,
                                           eid, x, y, mods, button, clicks, wheel);
        if ((*async_env)->ExceptionCheck(async_env))
            (*async_env)->ExceptionClear(async_env);
    });
}

@interface MseVtHostView : NSView
@end
@implementation MseVtHostView
- (NSView *)hitTest:(NSPoint)point
{
    /* Must honor hidden. Default NSView skips hidden views; this override did
       not, so mouseDown never reached Compose (hover still did — mouseMoved
       hits the DropdownMenu JDialog). Covering the canvas does not help while
       this view stays in the hierarchy and eats presses. */
    if (self.hidden || self.alphaValue < 0.01f)
        return nil;
    return NSPointInRect(point, self.frame) ? self : nil;
}
#define MSE_JNI_MOUSE(sel) - (void)sel:(NSEvent *)e { mse_java_mouse(e); }
MSE_JNI_MOUSE(mouseDown)
MSE_JNI_MOUSE(mouseUp)
MSE_JNI_MOUSE(mouseDragged)
MSE_JNI_MOUSE(rightMouseDown)
MSE_JNI_MOUSE(rightMouseUp)
MSE_JNI_MOUSE(otherMouseDown)
MSE_JNI_MOUSE(otherMouseUp)
MSE_JNI_MOUSE(scrollWheel)
#undef MSE_JNI_MOUSE
@end

/* One Canvas's overlay: an AVSampleBufferDisplayLayer in an MseVtHostView. _host and the
   live-resize fields are main-thread only. */
@interface MseVtOverlay : NSObject {
@public
    void *_layer; /* CFRetained AVSampleBufferDisplayLayer */
    NSView *_host;
    int _installed;
    int _unhosted;
    int _deferredShow;
    id _endLiveObs;
    NSWindow *_liveWin;
    int _pendingLayout;
    int _px, _py, _pw, _ph;
    pthread_mutex_t _vpMu;
    VPresenter *_vp; /* _vpMu */
    int _lost;       /* _vpMu */
}
@end

@implementation MseVtOverlay
- (instancetype)init
{
    self = [super init];
    if (self)
        pthread_mutex_init(&_vpMu, NULL);
    return self;
}

- (void)dealloc
{
    if (_endLiveObs)
        [NSNotificationCenter.defaultCenter removeObserver:_endLiveObs];
    if (_layer)
        CFRelease(_layer);
    pthread_mutex_destroy(&_vpMu);
}
@end

static jboolean (*mse_jawt_get_awt)(JNIEnv *, JAWT *);

/* Never @protocol(JAWT_SurfaceLayers): it emits a second copy of libawt_lwawt's protocol, and
   macOS 11's libobjc SIGBUSes rewriting its ref in read-only __DATA_CONST at System.load. */
static int mse_is_surface_layers(id sl)
{
    return sl && [sl respondsToSelector:@selector(layer)] && [sl respondsToSelector:@selector(windowLayer)];
}

static int jawt_bind(void)
{
    void *h;
    if (mse_jawt_get_awt)
        return 1;
    /* Kotlin System.loadLibrary("jawt") already mapped it; macOS 11's dyld doesn't match the
       leaf name against its @rpath install name, so the dlopen below is only a fallback. */
    mse_jawt_get_awt = (jboolean (*)(JNIEnv *, JAWT *))dlsym(RTLD_DEFAULT, "JAWT_GetAWT");
    if (mse_jawt_get_awt)
        return 1;
    h = dlopen("libjawt.dylib", RTLD_LAZY | RTLD_GLOBAL);
    if (!h) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt dlopen failed %s", dlerror() ? dlerror() : "?");
        return 0;
    }
    mse_jawt_get_awt = (jboolean (*)(JNIEnv *, JAWT *))dlsym(h, "JAWT_GetAWT");
    if (!mse_jawt_get_awt) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt dlsym JAWT_GetAWT failed");
        return 0;
    }
    return 1;
}

/* Always async. AWT's EDT is AppKit main: inlining during Canvas removeNotify /
   addNotify (mini player → Now Playing) re-enters AppKit while JAWT still owns
   the surface and freezes the UI. Never dispatch_sync. */
static void mse_on_main(void (^block)(void))
{
    dispatch_async(dispatch_get_main_queue(), block);
}

static NSView *mse_content_view(CALayer *wl);

static NSWindow *mse_awt_nswindow(JNIEnv *env, jobject awt)
{
    JAWT awt_api;
    JAWT_DrawingSurface *ds;
    JAWT_DrawingSurfaceInfo *dsi;
    id<JAWT_SurfaceLayers> sl;
    NSView *v = nil;
    jint lock;

    if (!awt)
        return nil;
    if (!jawt_bind())
        return nil;
    memset(&awt_api, 0, sizeof(awt_api));
    awt_api.version = JAWT_VERSION_1_7;
    if (mse_jawt_get_awt(env, &awt_api) == JNI_FALSE)
        return nil;
    ds = awt_api.GetDrawingSurface(env, awt);
    if (!ds)
        return nil;
    lock = ds->Lock(ds);
    if ((lock & JAWT_LOCK_ERROR) != 0) {
        awt_api.FreeDrawingSurface(ds);
        return nil;
    }
    dsi = ds->GetDrawingSurfaceInfo(ds);
    if (dsi) {
        sl = (__bridge id<JAWT_SurfaceLayers>)dsi->platformInfo;
        if (mse_is_surface_layers(sl))
            v = mse_content_view(sl.windowLayer);
        ds->FreeDrawingSurfaceInfo(dsi);
    }
    ds->Unlock(ds);
    awt_api.FreeDrawingSurface(ds);
    return v ? v.window : nil;
}

void mse_vt_order_window_below(void *envp, void *below, void *above)
{
    JNIEnv *env = envp;
    NSWindow *lo;
    NSWindow *hi;
    if (!env || !below || !above)
        return;
    lo = mse_awt_nswindow(env, below);
    hi = mse_awt_nswindow(env, above);
    if (!lo || !hi || lo == hi)
        return;
    mse_on_main(^{
        [lo orderWindow:NSWindowBelow relativeTo:hi.windowNumber];
    });
}

static NSWindow *mse_named_window(NSString *title)
{
    for (NSWindow *w in NSApp.windows) {
        if ([w.title isEqualToString:title])
            return w;
    }
    return nil;
}

static void mse_fs_layout_unwatch(void)
{
    NSNotificationCenter *nc = NSNotificationCenter.defaultCenter;
    if (mse_fs_resize_obs) {
        [nc removeObserver:mse_fs_resize_obs];
        mse_fs_resize_obs = nil;
    }
    if (mse_fs_move_obs) {
        [nc removeObserver:mse_fs_move_obs];
        mse_fs_move_obs = nil;
    }
}

static void mse_fs_timer_stop(void)
{
    mse_fs_gen++;
    mse_fs_layout_unwatch();
}

static void mse_fs_restack(void)
{
    NSWindow *ui = mse_named_window(@"YMusic");
    NSWindow *video = mse_named_window(@"YMusic video");
    BOOL fs;
    if (!ui || !video || ui == video)
        return;
    fs = (ui.styleMask & NSWindowStyleMaskFullScreen) != 0;
    if (video.parentWindow)
        [video.parentWindow removeChildWindow:video];
    ui.level = fs ? NSFloatingWindowLevel : NSNormalWindowLevel;
    video.level = NSNormalWindowLevel;
    video.collectionBehavior = NSWindowCollectionBehaviorManaged |
                               NSWindowCollectionBehaviorFullScreenNone |
                               NSWindowCollectionBehaviorMoveToActiveSpace;
    video.ignoresMouseEvents = fs;
    [ui orderFrontRegardless];
    [video orderWindow:NSWindowBelow relativeTo:ui.windowNumber];
}

static void mse_fs_burst(unsigned gen, int left)
{
    if (gen != mse_fs_gen)
        return;
    mse_fs_restack();
    if (left <= 0)
        return;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 50 * NSEC_PER_MSEC),
                   dispatch_get_main_queue(), ^{
                       mse_fs_burst(gen, left - 1);
                   });
}

static void mse_fs_timer_start(void)
{
    NSNotificationCenter *nc = NSNotificationCenter.defaultCenter;
    unsigned gen;
    mse_fs_gen++;
    gen = mse_fs_gen;
    mse_fs_layout_unwatch();
    mse_fs_resize_obs = [nc addObserverForName:NSWindowDidResizeNotification
                                        object:nil
                                         queue:NSOperationQueue.mainQueue
                                    usingBlock:^(NSNotification *note) {
                                        NSWindow *w = note.object;
                                        NSString *t = w.title;
                                        if (![t isEqualToString:@"YMusic"] &&
                                            ![t isEqualToString:@"YMusic video"])
                                            return;
                                        mse_fs_restack();
                                    }];
    mse_fs_move_obs = [nc addObserverForName:NSWindowDidMoveNotification
                                      object:nil
                                       queue:NSOperationQueue.mainQueue
                                  usingBlock:^(NSNotification *note) {
                                      NSWindow *w = note.object;
                                      NSString *t = w.title;
                                      if (![t isEqualToString:@"YMusic"] &&
                                          ![t isEqualToString:@"YMusic video"])
                                          return;
                                      mse_fs_restack();
                                  }];
    mse_fs_burst(gen, 8);
}

static void mse_notify_fs(int on)
{
    JNIEnv *env = NULL;
    if (!mse_jvm || !mse_native_cls || !mse_fs_mid)
        return;
    if ((*mse_jvm)->GetEnv(mse_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        (*mse_jvm)->AttachCurrentThread(mse_jvm, (void **)&env, NULL);
    if (!env)
        return;
    (*env)->CallStaticVoidMethod(env, mse_native_cls, mse_fs_mid, on ? JNI_TRUE : JNI_FALSE);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
}

static void mse_fs_watch(NSWindow *win)
{
    NSNotificationCenter *nc = NSNotificationCenter.defaultCenter;
    if (!win)
        return;
    if (mse_fs_enter_obs) {
        [nc removeObserver:mse_fs_enter_obs];
        mse_fs_enter_obs = nil;
    }
    if (mse_fs_exit_obs) {
        [nc removeObserver:mse_fs_exit_obs];
        mse_fs_exit_obs = nil;
    }
    mse_fs_enter_obs = [nc addObserverForName:NSWindowDidEnterFullScreenNotification
                                      object:win
                                       queue:nil
                                  usingBlock:^(NSNotification *note) {
                                      (void)note;
                                      mse_fs_restack();
                                      mse_fs_timer_start();
                                      mse_notify_fs(1);
                                  }];
    mse_fs_exit_obs = [nc addObserverForName:NSWindowDidExitFullScreenNotification
                                     object:win
                                      queue:nil
                                 usingBlock:^(NSNotification *note) {
                                     (void)note;
                                     mse_fs_timer_stop();
                                     mse_fs_restack();
                                     mse_notify_fs(0);
                                 }];
}

void mse_vt_prepare_fullscreen(void *envp, void *primary)
{
    JNIEnv *env = envp;
    NSWindow *ui;
    if (!env || !primary)
        return;
    mse_bind_mouse_jni(env);
    ui = mse_awt_nswindow(env, primary);
    if (!ui)
        ui = mse_named_window(@"YMusic");
    if (!ui)
        return;
    mse_on_main(^{
        ui.collectionBehavior |= NSWindowCollectionBehaviorFullScreenPrimary |
                                 NSWindowCollectionBehaviorManaged;
        mse_fs_watch(ui);
    });
}

void mse_vt_toggle_fullscreen(void *envp, void *primary)
{
    JNIEnv *env = envp;
    NSWindow *ui;
    if (!env)
        return;
    ui = primary ? mse_awt_nswindow(env, primary) : nil;
    if (!ui)
        ui = mse_named_window(@"YMusic");
    if (!ui)
        return;
    mse_on_main(^{
        [ui toggleFullScreen:nil];
    });
}

static NSView *mse_content_view(CALayer *wl)
{
    NSView *v = nil;
    CALayer *p;
    Class theme;
    if ([wl.delegate isKindOfClass:[NSView class]])
        v = (NSView *)wl.delegate;
    else {
        for (p = wl; p; p = p.superlayer) {
            if ([p.delegate isKindOfClass:[NSView class]]) {
                v = (NSView *)p.delegate;
                break;
            }
        }
    }
    if (!v)
        v = [NSApp keyWindow].contentView;
    theme = NSClassFromString(@"NSThemeFrame");
    if (theme && [v isKindOfClass:theme])
        v = v.window.contentView;
    if (v.window.contentView)
        v = v.window.contentView;
    return v;
}

static int mse_java_screen_rect(JNIEnv *env, jobject obj, jint *x, jint *y, jint *w, jint *h)
{
    jclass cls, ptcls;
    jmethodID getW, getH, getLoc;
    jobject pt;
    jfieldID fx, fy;

    cls = (*env)->GetObjectClass(env, obj);
    getW = (*env)->GetMethodID(env, cls, "getWidth", "()I");
    getH = (*env)->GetMethodID(env, cls, "getHeight", "()I");
    getLoc = (*env)->GetMethodID(env, cls, "getLocationOnScreen", "()Ljava/awt/Point;");
    if (!getW || !getH || !getLoc)
        return -1;
    *w = (*env)->CallIntMethod(env, obj, getW);
    *h = (*env)->CallIntMethod(env, obj, getH);
    pt = (*env)->CallObjectMethod(env, obj, getLoc);
    if ((*env)->ExceptionCheck(env) || !pt) {
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        return -1;
    }
    ptcls = (*env)->GetObjectClass(env, pt);
    fx = (*env)->GetFieldID(env, ptcls, "x", "I");
    fy = (*env)->GetFieldID(env, ptcls, "y", "I");
    if (!fx || !fy)
        return -1;
    *x = (*env)->GetIntField(env, pt, fx);
    *y = (*env)->GetIntField(env, pt, fy);
    return (*w > 0 && *h > 0) ? 0 : -1;
}

static NSRect mse_screen_to_view(NSView *parent, jint x, jint y, jint w, jint h)
{
    NSRect screen, win;
    CGFloat top = NSMaxY(parent.window.screen.frame);

    screen = NSMakeRect((CGFloat)x, top - (CGFloat)y - (CGFloat)h, (CGFloat)w, (CGFloat)h);
    win = [parent.window convertRectFromScreen:screen];
    return [parent convertRect:win fromView:nil];
}

static void ov_clear_live_obs(MseVtOverlay *ov)
{
    if (!ov->_endLiveObs)
        return;
    [NSNotificationCenter.defaultCenter removeObserver:ov->_endLiveObs];
    ov->_endLiveObs = nil;
    ov->_liveWin = nil;
}

static void ov_apply_host_frame(MseVtOverlay *ov, int x, int y, int w, int h)
{
    NSView *host = ov->_host;
    NSView *parent = host.superview;
    NSRect frame;
    if (!host || !parent)
        return;
    frame = mse_screen_to_view(parent, x, y, w, h);
    if (NSEqualRects(host.frame, frame))
        return;
    host.frame = frame;
}

static void ov_watch_live_resize(MseVtOverlay *ov, NSWindow *win)
{
    __weak MseVtOverlay *weak = ov;
    if (!win)
        return;
    if (ov->_liveWin == win && ov->_endLiveObs)
        return;
    ov_clear_live_obs(ov);
    ov->_liveWin = win;
    ov->_endLiveObs = [NSNotificationCenter.defaultCenter
        addObserverForName:NSWindowDidEndLiveResizeNotification
                    object:win
                     queue:nil
                usingBlock:^(NSNotification *note) {
                    MseVtOverlay *o = weak;
                    (void)note;
                    if (o && o->_pendingLayout) {
                        o->_pendingLayout = 0;
                        ov_apply_host_frame(o, o->_px, o->_py, o->_pw, o->_ph);
                    }
                }];
}

/* Main thread: the view could not be installed, so the presenter paints on the CPU. */
static void ov_lost(MseVtOverlay *ov)
{
    VPresenter *p = NULL;
    pthread_mutex_lock(&ov->_vpMu);
    ov->_lost = 1;
    if (ov->_vp && vp_retain_live(ov->_vp))
        p = ov->_vp;
    pthread_mutex_unlock(&ov->_vpMu);
    if (!p)
        return;
    vp_gpu_lost(p);
    vpresent_release(p);
}

static MseVtOverlay *ov_create(JNIEnv *env, jobject obj, jint x, jint y, jint w, jint h)
{
    jclass canvas;
    JAWT awt;
    JAWT_DrawingSurface *ds;
    JAWT_DrawingSurfaceInfo *dsi;
    id<JAWT_SurfaceLayers> sl;
    CALayer *wl;
    AVSampleBufferDisplayLayer *layer;
    MseVtOverlay *ov;

    if (!jawt_bind())
        return nil;
    canvas = (*env)->FindClass(env, "java/awt/Canvas");
    if (!canvas || !(*env)->IsInstanceOf(env, obj, canvas)) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt not a Canvas");
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        return nil;
    }
    memset(&awt, 0, sizeof(awt));
    awt.version = JAWT_VERSION_1_7;
    if (mse_jawt_get_awt(env, &awt) == JNI_FALSE) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt GetAWT failed");
        return nil;
    }
    ds = awt.GetDrawingSurface(env, obj);
    if (!ds) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt GetDrawingSurface failed");
        return nil;
    }
    if ((ds->Lock(ds) & JAWT_LOCK_ERROR) != 0) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt Lock failed");
        awt.FreeDrawingSurface(ds);
        return nil;
    }
    dsi = ds->GetDrawingSurfaceInfo(ds);
    if (!dsi || !dsi->platformInfo) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt GetDrawingSurfaceInfo failed");
        ds->Unlock(ds);
        awt.FreeDrawingSurface(ds);
        return nil;
    }
    sl = (__bridge id<JAWT_SurfaceLayers>)dsi->platformInfo;
    if (!mse_is_surface_layers(sl)) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt platformInfo is not SurfaceLayers");
        ds->FreeDrawingSurfaceInfo(dsi);
        ds->Unlock(ds);
        awt.FreeDrawingSurface(ds);
        return nil;
    }
    wl = sl.windowLayer;
    ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);

    layer = [AVSampleBufferDisplayLayer layer];
    layer.videoGravity = AVLayerVideoGravityResizeAspect;
    layer.backgroundColor = CGColorGetConstantColor(kCGColorBlack);
    layer.actions = @{ @"contents": [NSNull null], @"bounds": [NSNull null], @"frame": [NSNull null] };
    ov = [[MseVtOverlay alloc] init];
    ov->_layer = (void *)CFBridgingRetain(layer);
    mse_on_main(^{
        NSView *parent;
        NSView *host;
        NSRect frame;
        if (ov->_unhosted)
            return;
        parent = mse_content_view(wl);
        if (!parent) {
            mse_critf(MSE_LOG_WARN, "mse-v jawt no content view");
            ov_lost(ov);
            return;
        }
        /* Child of AWTView: the picture is visible. A sibling under AWTView is a
           black hole (Metal does not composite through, and NSThemeFrame rejects
           unknown subviews). Compose chrome over the hole is copied via JNI. */
        frame = mse_screen_to_view(parent, x, y, w, h);
        host = [[MseVtHostView alloc] initWithFrame:frame];
        host.wantsLayer = YES;
        host.layer = (__bridge AVSampleBufferDisplayLayer *)ov->_layer;
        [parent addSubview:host];
        host.window.acceptsMouseMovedEvents = YES;
        ov->_host = host;
        ov->_installed = 1;
        ov_watch_live_resize(ov, host.window);
        mse_critf(MSE_LOG_INFO, "mse-v jawt layer attached %.0fx%.0f @ %.0f,%.0f parent=%s",
                  frame.size.width, frame.size.height, frame.origin.x, frame.origin.y,
                  parent.className.UTF8String ?: "?");
    });
    return ov;
}

void vt_overlay_bind(void *handle, VPresenter *p)
{
    MseVtOverlay *ov = (__bridge MseVtOverlay *)handle;
    int lost;
    pthread_mutex_lock(&ov->_vpMu);
    ov->_vp = p;
    lost = ov->_lost;
    pthread_mutex_unlock(&ov->_vpMu);
    if (p && lost)
        vp_gpu_lost(p);
}

int vt_overlay_hosted(void *handle)
{
    MseVtOverlay *ov = (__bridge MseVtOverlay *)handle;
    return ov && ov->_installed && !ov->_unhosted;
}

void vt_overlay_layout(void *handle, int x, int y, int w, int h)
{
    MseVtOverlay *ov = (__bridge MseVtOverlay *)handle;
    if (w <= 0 || h <= 0)
        return;
    /* Always async: inlining on main from AWT doLayout set the NSView frame,
       which re-entered AWT layout (~300% CPU). Skip while the user is dragging
       the window edge — apply once on NSWindowDidEndLiveResize. */
    dispatch_async(dispatch_get_main_queue(), ^{
        NSView *host = ov->_host;
        NSWindow *win;
        if (!host)
            return;
        win = host.window;
        ov_watch_live_resize(ov, win);
        if (win && win.inLiveResize) {
            ov->_px = x;
            ov->_py = y;
            ov->_pw = w;
            ov->_ph = h;
            ov->_pendingLayout = 1;
            return;
        }
        ov->_pendingLayout = 0;
        ov_apply_host_frame(ov, x, y, w, h);
    });
}

void vt_overlay_set_hidden(void *handle, int hid)
{
    MseVtOverlay *ov = (__bridge MseVtOverlay *)handle;
    if (!hid) {
        /* Stays hidden until vt_overlay_reveal (next presented frame). */
        ov->_deferredShow = 1;
        return;
    }
    ov->_deferredShow = 0;
    if ([NSThread isMainThread])
        ov->_host.hidden = YES;
    else
        mse_on_main(^{
            ov->_host.hidden = YES;
        });
}

void vt_overlay_reveal(void *handle)
{
    MseVtOverlay *ov = (__bridge MseVtOverlay *)handle;
    if (!ov->_deferredShow)
        return;
    ov->_deferredShow = 0;
    if ([NSThread isMainThread])
        ov->_host.hidden = NO;
    else
        mse_on_main(^{
            ov->_host.hidden = NO;
        });
}

void vt_overlay_unhost(void *handle)
{
    MseVtOverlay *ov = (__bridge MseVtOverlay *)handle;
    if (ov->_unhosted)
        return;
    ov->_unhosted = 1;
    mse_on_main(^{
        NSView *v = ov->_host;
        ov->_host = nil;
        ov->_pendingLayout = 0;
        ov_clear_live_obs(ov);
        [v removeFromSuperview];
    });
}

void vt_overlay_release(void *handle)
{
    if (handle)
        CFRelease(handle);
}

static VPresenter *overlay_presenter(JNIEnv *env, jobject obj, jint x, jint y, jint w, jint h,
                                     const VpSink *s)
{
    MseVtOverlay *ov = ov_create(env, obj, x, y, w, h);
    void *handle;
    VPresenter *p;
    if (!ov)
        return NULL;
    handle = (void *)CFBridgingRetain(ov);
    p = vp_vt_create(ov->_layer, handle, s);
    if (!p) {
        vt_overlay_unhost(handle);
        vt_overlay_release(handle);
    }
    return p;
}

VPresenter *vpresent_create_vt_overlay(void *envp, void *canvas, const VpSink *s)
{
    JNIEnv *env = (JNIEnv *)envp;
    jint x = 0, y = 0, w = 0, h = 0;
    if (!env || !canvas)
        return NULL;
    mse_bind_mouse_jni(env);
    /* Not on screen yet: an empty frame until vpresent_vt_layout. */
    if (mse_java_screen_rect(env, (jobject)canvas, &x, &y, &w, &h) != 0)
        x = y = w = h = 0;
    return overlay_presenter(env, (jobject)canvas, x, y, w, h, s);
}

/* Owner-token API over one process-wide overlay presenter. Each hold owns a presenter reference;
   g_shim is the overlay on screen, cleared when its last hold goes. */
typedef struct ShimHold {
    struct ShimHold *next;
    const void *owner;
    VPresenter *p;
    int last;
} ShimHold;

static pthread_mutex_t g_shim_mu = PTHREAD_MUTEX_INITIALIZER;
static VPresenter *g_shim;
static ShimHold *g_holds;

/* Caller holds g_shim_mu. p's layer, held by owner; NULL when out of memory. */
static void *shim_hold_locked(const void *owner, VPresenter *p)
{
    ShimHold *h;
    for (h = g_holds; h; h = h->next) {
        if (h->owner == owner && h->p == p)
            return vp_vt_layer(p);
    }
    h = calloc(1, sizeof(*h));
    if (!h)
        return NULL;
    vpresent_retain(p);
    h->owner = owner;
    h->p = p;
    h->next = g_holds;
    g_holds = h;
    return vp_vt_layer(p);
}

static VPresenter *shim_current(void)
{
    VPresenter *p;
    pthread_mutex_lock(&g_shim_mu);
    p = g_shim;
    vpresent_retain(p);
    pthread_mutex_unlock(&g_shim_mu);
    return p;
}

void *mse_vt_attach_component(void *envp, void *component, const void *owner)
{
    JNIEnv *env = (JNIEnv *)envp;
    jobject obj = (jobject)component;
    jint jx = 0, jy = 0, jw = 0, jh = 0;
    VPresenter *p, *old = NULL;
    void *layer = NULL;

    if (!env || !obj)
        return NULL;
    mse_bind_mouse_jni(env);
    if (mse_java_screen_rect(env, obj, &jx, &jy, &jw, &jh) != 0) {
        pthread_mutex_lock(&g_shim_mu);
        if (g_shim)
            layer = shim_hold_locked(owner, g_shim);
        pthread_mutex_unlock(&g_shim_mu);
        return layer;
    }
    p = shim_current();
    if (p && vt_overlay_hosted(vp_vt_overlay(p))) {
        vpresent_vt_layout(p, jx, jy, jw, jh);
        pthread_mutex_lock(&g_shim_mu);
        if (g_shim == p)
            layer = shim_hold_locked(owner, p);
        pthread_mutex_unlock(&g_shim_mu);
        vpresent_release(p);
        return layer;
    }
    vpresent_release(p);
    p = overlay_presenter(env, obj, jx, jy, jw, jh, NULL);
    if (!p)
        return NULL;
    pthread_mutex_lock(&g_shim_mu);
    layer = shim_hold_locked(owner, p);
    if (layer) {
        old = g_shim;
        g_shim = p;
    }
    pthread_mutex_unlock(&g_shim_mu);
    if (old)
        vpresent_detach(old);
    if (!layer)
        vpresent_detach(p);
    vpresent_release(p);
    return layer;
}

void mse_vt_release_component(const void *owner, const void *keep)
{
    ShimHold **it, *h, *o, *gone = NULL;
    pthread_mutex_lock(&g_shim_mu);
    it = &g_holds;
    while ((h = *it) != NULL) {
        if (h->owner != owner || (keep && vp_vt_layer(h->p) == keep)) {
            it = &h->next;
            continue;
        }
        *it = h->next;
        h->next = gone;
        gone = h;
    }
    for (h = gone; h; h = h->next) {
        h->last = 1;
        for (o = g_holds; o; o = o->next) {
            if (o->p == h->p) {
                h->last = 0;
                break;
            }
        }
        if (h->last && g_shim == h->p)
            g_shim = NULL;
    }
    pthread_mutex_unlock(&g_shim_mu);
    while (gone) {
        h = gone;
        gone = h->next;
        if (h->last)
            vpresent_detach(h->p);
        vpresent_release(h->p);
        free(h);
    }
}

void mse_vt_shim_shown(void *layer)
{
    VPresenter *p = shim_current();
    if (!p)
        return;
    if (vp_vt_layer(p) == layer)
        vt_overlay_reveal(vp_vt_overlay(p));
    vpresent_release(p);
}

void mse_vt_set_overlay_hidden(int hid)
{
    VPresenter *p = shim_current();
    vpresent_vt_set_hidden(p, hid);
    vpresent_release(p);
}

void mse_vt_layout_screen(int x, int y, int w, int h)
{
    VPresenter *p = shim_current();
    vpresent_vt_layout(p, x, y, w, h);
    vpresent_release(p);
}
#endif
