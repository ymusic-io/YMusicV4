/* The Chrome 155 (macOS) window interfaces that neither env.raw.js nor idl_stubs.raw.js defines, as
 * "Name[:Parent[:length]]", parents first. BotGuard enumerates the globals and probes rarer interfaces
 * one at a time, so each gets an illegal-constructor stub; globals Chrome does not expose on
 * youtube.com are removed. An interface added to env.raw.js or idl_stubs.raw.js must leave this list.
 */
(function () {
  var CHROME =
    "WebKitCSSMatrix:DOMMatrixReadOnly,FeaturePolicy," +
    "XMLHttpRequestEventTarget:EventTarget,XMLHttpRequestUpload:XMLHttpRequestEventTarget," +
    "WritableStreamDefaultWriter::1,WritableStreamDefaultController,WritableStream," +
    "WindowControlsOverlayGeometryChangeEvent:Event:2,WindowControlsOverlay:EventTarget,WebGLObject," +
    "WebGLVertexArrayObject:WebGLObject,WebGLUniformLocation,WebGLTransformFeedback:WebGLObject," +
    "WebGLTexture:WebGLObject,WebGLSync:WebGLObject,WebGLShaderPrecisionFormat," +
    "WebGLShader:WebGLObject,WebGLSampler:WebGLObject,WebGLRenderbuffer:WebGLObject," +
    "WebGLQuery:WebGLObject,WebGLProgram:WebGLObject,WebGLFramebuffer:WebGLObject," +
    "WebGLContextEvent:Event:1,WebGLBuffer:WebGLObject,WebGLActiveInfo," +
    "VirtualKeyboardGeometryChangeEvent:Event:1,ViewTransitionTypeSet,ViewTransition," +
    "AnimationTimeline,ScrollTimeline:AnimationTimeline,ViewTimeline:ScrollTimeline," +
    "VideoPlaybackQuality,VideoFrame::1,VideoColorSpace,URLPattern,TransitionEvent:Event:1," +
    "TransformStreamDefaultController,TransformStream,Touch::1,TextUpdateEvent:Event:1," +
    "TextFormatUpdateEvent:Event:1,TextFormat,TextEncoderStream,TextDecoderStream," +
    "TaskSignal:AbortSignal,TaskPriorityChangeEvent:Event:2,TaskController:AbortController," +
    "TaskAttributionTiming:PerformanceEntry,SyncManager,Subscriber,StylePropertyMapReadOnly," +
    "StylePropertyMap:StylePropertyMapReadOnly,SourceBufferList:EventTarget," +
    "SecurityPolicyViolationEvent:Event:1,ScriptProcessorNode:AudioNode," +
    "ScreenOrientation:EventTarget,Scheduling,Scheduler,SVGViewElement:SVGElement," +
    "SVGUseElement:SVGGraphicsElement,SVGUnitTypes,SVGTransformList,SVGTransform," +
    "SVGTitleElement:SVGElement,SVGTextContentElement:SVGGraphicsElement," +
    "SVGTextPositioningElement:SVGTextContentElement,SVGTextPathElement:SVGTextContentElement," +
    "SVGTextElement:SVGTextPositioningElement,SVGTSpanElement:SVGTextPositioningElement," +
    "SVGSymbolElement:SVGGraphicsElement,SVGSwitchElement:SVGGraphicsElement," +
    "SVGStyleElement:SVGElement,SVGStringList,SVGStopElement:SVGElement," +
    "SVGAnimationElement:SVGElement,SVGSetElement:SVGAnimationElement,SVGScriptElement:SVGElement," +
    "SVGGeometryElement:SVGGraphicsElement,SVGRectElement:SVGGeometryElement,SVGRect," +
    "SVGGradientElement:SVGElement,SVGRadialGradientElement:SVGGradientElement," +
    "SVGPreserveAspectRatio,SVGPolylineElement:SVGGeometryElement," +
    "SVGPolygonElement:SVGGeometryElement,SVGPointList,SVGPoint,SVGPatternElement:SVGElement," +
    "SVGPathElement:SVGGeometryElement,SVGNumberList,SVGNumber,SVGMetadataElement:SVGElement," +
    "SVGMatrix,SVGMaskElement:SVGElement,SVGMarkerElement:SVGElement,SVGMPathElement:SVGElement," +
    "SVGLinearGradientElement:SVGGradientElement,SVGLineElement:SVGGeometryElement,SVGLengthList," +
    "SVGLength,SVGImageElement:SVGGraphicsElement,SVGGElement:SVGGraphicsElement," +
    "SVGFilterElement:SVGElement,SVGFETurbulenceElement:SVGElement,SVGFETileElement:SVGElement," +
    "SVGFESpotLightElement:SVGElement,SVGFESpecularLightingElement:SVGElement," +
    "SVGFEPointLightElement:SVGElement,SVGFEOffsetElement:SVGElement," +
    "SVGFEMorphologyElement:SVGElement,SVGFEMergeNodeElement:SVGElement,SVGFEMergeElement:SVGElement," +
    "SVGFEImageElement:SVGElement,SVGFEGaussianBlurElement:SVGElement," +
    "SVGFEFuncRElement:SVGComponentTransferFunctionElement," +
    "SVGFEFuncGElement:SVGComponentTransferFunctionElement," +
    "SVGFEFuncBElement:SVGComponentTransferFunctionElement," +
    "SVGFEFuncAElement:SVGComponentTransferFunctionElement,SVGFEFloodElement:SVGElement," +
    "SVGFEDropShadowElement:SVGElement,SVGFEDistantLightElement:SVGElement," +
    "SVGFEDisplacementMapElement:SVGElement,SVGFEDiffuseLightingElement:SVGElement," +
    "SVGFEConvolveMatrixElement:SVGElement,SVGFECompositeElement:SVGElement," +
    "SVGFEBlendElement:SVGElement,SVGEllipseElement:SVGGeometryElement,SVGDescElement:SVGElement," +
    "SVGDefsElement:SVGGraphicsElement,SVGClipPathElement:SVGElement," +
    "SVGCircleElement:SVGGeometryElement,SVGAnimatedTransformList,SVGAnimatedString,SVGAnimatedRect," +
    "SVGAnimatedPreserveAspectRatio,SVGAnimatedNumber,SVGAnimatedLengthList,SVGAnimatedLength," +
    "SVGAnimatedInteger,SVGAnimatedEnumeration,SVGAnimatedBoolean,SVGAnimatedAngle," +
    "SVGAnimateTransformElement:SVGAnimationElement,SVGAnimateMotionElement:SVGAnimationElement," +
    "SVGAnimateElement:SVGAnimationElement,SVGAngle,SVGAElement:SVGGraphicsElement," +
    "ResizeObserverSize,ReportBody,ReadableStreamDefaultController,ReadableStreamBYOBRequest," +
    "ReadableStreamBYOBReader::1,ReadableByteStreamController,RTCTrackEvent:Event:2,RTCStatsReport," +
    "RTCSessionDescription,RTCSctpTransport:EventTarget,RTCRtpTransceiver,RTCRtpSender," +
    "RTCRtpReceiver,RTCPeerConnectionIceEvent:Event:1,RTCPeerConnectionIceErrorEvent:Event:2," +
    "RTCIceTransport:EventTarget,RTCIceCandidate,RTCErrorEvent:Event:2,RTCError:DOMException:1," +
    "RTCEncodedVideoFrame::1,RTCEncodedAudioFrame::1,RTCDtlsTransport:EventTarget," +
    "RTCDataChannelEvent:Event:2,RTCDTMFToneChangeEvent:Event:1,RTCDTMFSender:EventTarget," +
    "RTCCertificate,ProgressEvent:Event:1,PictureInPictureWindow:EventTarget," +
    "PictureInPictureEvent:Event:2,PermissionsPolicy,PerformanceTimingConfidence," +
    "PerformanceSoftNavigation:PerformanceEntry,PerformanceServerTiming," +
    "PerformanceResourceTiming:PerformanceEntry,PerformancePaintTiming:PerformanceEntry," +
    "PerformanceObserverEntryList,PerformanceNavigationTiming:PerformanceResourceTiming," +
    "PerformanceMeasure:PerformanceEntry,PerformanceMark:PerformanceEntry:1," +
    "PerformanceLongTaskTiming:PerformanceEntry,PerformanceEventTiming:PerformanceEntry," +
    "PerformanceElementTiming:PerformanceEntry,OverconstrainedError:DOMException:1," +
    "OfflineAudioCompletionEvent:Event:2,Observable::1,NodeFilter,NavigatorUAData," +
    "MediaStreamTrackVideoStats,MediaStreamTrackProcessor::1," +
    "MediaStreamTrackGenerator:MediaStreamTrack:1,MediaStreamTrackEvent:Event:2," +
    "MediaStreamEvent:Event:1,MediaSourceHandle,MediaEncryptedEvent:Event:1,MediaCapabilities," +
    "MathMLElement:Element,LayoutShiftAttribution,LayoutShift:PerformanceEntry," +
    "LargestContentfulPaint:PerformanceEntry,AnimationEffect,KeyframeEffect:AnimationEffect:1," +
    "InterestEvent:Event:1,InteractionContentfulPaint:PerformanceEntry,MediaDeviceInfo," +
    "InputDeviceInfo:MediaDeviceInfo,InputDeviceCapabilities,Ink,IdleDeadline," +
    "IDBVersionChangeEvent:Event:1,IDBTransaction:EventTarget,IDBRequest:EventTarget,IDBRecord," +
    "IDBOpenDBRequest:IDBRequest,IDBObjectStore,IDBKeyRange,IDBIndex,IDBDatabase:EventTarget," +
    "IDBCursor,IDBCursorWithValue:IDBCursor,Highlight,GeolocationPositionError,GeolocationPosition," +
    "GeolocationCoordinates,Geolocation,GamepadHapticActuator,GamepadEvent:Event:1,GamepadButton," +
    "Gamepad,FontFaceSetLoadEvent:Event:1,EventCounts,EncodedVideoChunk::1,EncodedAudioChunk::1," +
    "EditContext:EventTarget,DocumentTimeline:AnimationTimeline,DelegatedInkTrailPresenter," +
    "DOMRectList,DOMError::1,CountQueuingStrategy::1,ContentVisibilityAutoStateChangeEvent:Event:1," +
    "CloseEvent:Event:1,CharacterBoundsUpdateEvent:Event:1," +
    "CanvasCaptureMediaStreamTrack:MediaStreamTrack,CSSViewTransitionRule:CSSRule," +
    "CSSVariableReferenceValue::1,Animation:EventTarget,CSSTransition:Animation," +
    "CSSConditionRule:CSSGroupingRule,CSSSupportsRule:CSSConditionRule," +
    "CSSStartingStyleRule:CSSGroupingRule,CSSSkewY:CSSTransformComponent:1," +
    "CSSSkewX:CSSTransformComponent:1,CSSSkew:CSSTransformComponent:2,CSSScopeRule:CSSGroupingRule," +
    "CSSPropertyRule:CSSRule,CSSPositionValue:CSSStyleValue:2,CSSPositionTryRule:CSSRule," +
    "CSSPositionTryDescriptors:CSSStyleDeclaration,CSSNumericArray,CSSNestedDeclarations:CSSRule," +
    "CSSMathValue:CSSNumericValue,CSSMathSum:CSSMathValue,CSSMathProduct:CSSMathValue," +
    "CSSMathNegate:CSSMathValue:1,CSSMathMin:CSSMathValue,CSSMathMax:CSSMathValue," +
    "CSSMathInvert:CSSMathValue:1,CSSMathClamp:CSSMathValue:3,CSSLayerStatementRule:CSSRule," +
    "CSSLayerBlockRule:CSSGroupingRule,CSSKeyframesRule:CSSRule,CSSKeyframeRule:CSSRule," +
    "CSSImageValue:CSSStyleValue,CSSFontPaletteValuesRule:CSSRule,CSSFontFaceRule:CSSRule," +
    "CSSCounterStyleRule:CSSRule,CSSContainerRule:CSSConditionRule,CSSAnimation:Animation," +
    "CSPViolationReportBody:ReportBody,ByteLengthQueuingStrategy::1," +
    "BrowserCaptureMediaStreamTrack:MediaStreamTrack,BlobEvent:Event:2," +
    "BeforeInstallPromptEvent:Event:1,BaseAudioContext:EventTarget,AudioSinkInfo," +
    "AudioScheduledSourceNode:AudioNode,AudioProcessingEvent:Event:2,AudioParamMap,AudioData::1," +
    "AnimationPlaybackEvent:Event:1,AnimationEvent:Event:1,Sensor:EventTarget," +
    "OrientationSensor:Sensor,AbsoluteOrientationSensor:OrientationSensor,Accelerometer:Sensor," +
    "AudioDecoder:EventTarget:1,AudioEncoder:EventTarget:1,AudioWorklet:Worklet," +
    "BatteryManager:EventTarget,Cache,Clipboard:EventTarget,ClipboardChangeEvent:Event," +
    "ClipboardItem::1,CookieChangeEvent:Event:1,CookieStore:EventTarget,CookieStoreManager," +
    "CreateMonitor:EventTarget,Credential,CredentialsContainer,CryptoKey," +
    "DeviceMotionEventAcceleration,DeviceMotionEventRotationRate," +
    "FederatedCredential:Credential:1,GPUAdapterInfo,GPUCommandBuffer,GPUCompilationInfo," +
    "GPUCompilationMessage,GPUComputePassEncoder,GPUDeviceLostInfo,GPUError,GPUExternalTexture," +
    "GPUInternalError:GPUError:1,GPUOutOfMemoryError:GPUError:1,GPUPipelineError:DOMException:1," +
    "GPURenderBundle,GPURenderBundleEncoder,GPURenderPassEncoder,GPUSupportedFeatures," +
    "GPUSupportedLimits,GPUUncapturedErrorEvent:Event:2,GPUValidationError:GPUError:1," +
    "GravitySensor:Accelerometer,Gyroscope:Sensor,IdleDetector:EventTarget,ImageCapture::1," +
    "ImageDecoder::1,ImageTrack,ImageTrackList,Keyboard,KeyboardLayoutMap," +
    "LinearAccelerationSensor:Accelerometer,MIDIAccess:EventTarget,MIDIConnectionEvent:Event:1," +
    "MIDIPort:EventTarget,MIDIInput:MIDIPort,MIDIInputMap,MIDIMessageEvent:Event:1," +
    "MIDIOutput:MIDIPort,MIDIOutputMap,MediaDevices:EventTarget,MediaKeyMessageEvent:Event:2," +
    "MediaKeySession:EventTarget,MediaKeyStatusMap,MediaKeySystemAccess,MediaKeys," +
    "NavigationPreloadManager,NavigatorManagedData:EventTarget,PasswordCredential:Credential:1," +
    "ProtectedAudience,RelativeOrientationSensor:OrientationSensor,ScreenDetailed:Screen," +
    "ScreenDetails:EventTarget,SensorErrorEvent:Event:2,ServiceWorkerRegistration:EventTarget," +
    "StorageManager,VideoDecoder:EventTarget:1,VideoEncoder:EventTarget:1," +
    "VirtualKeyboard:EventTarget,WGSLLanguageFeatures,WebTransport::1," +
    "WebTransportBidirectionalStream,WebTransportDatagramDuplexStream,WebTransportError:DOMException," +
    "XRDOMOverlayState,XRLayer:EventTarget,AudioPlaybackStats,AuthenticatorResponse," +
    "AuthenticatorAssertionResponse:AuthenticatorResponse," +
    "AuthenticatorAttestationResponse:AuthenticatorResponse,PublicKeyCredential:Credential," +
    "BarcodeDetector,Bluetooth:EventTarget,BluetoothCharacteristicProperties," +
    "BluetoothDevice:EventTarget,BluetoothRemoteGATTCharacteristic:EventTarget," +
    "BluetoothRemoteGATTDescriptor,BluetoothRemoteGATTServer,BluetoothRemoteGATTService," +
    "CaptureController:EventTarget,CrashReportContext,DevicePosture:EventTarget," +
    "DigitalCredential:Credential,DocumentPictureInPicture:EventTarget,EyeDropper,FetchLaterResult," +
    "FileSystemHandle,FileSystemDirectoryHandle:FileSystemHandle," +
    "FileSystemFileHandle:FileSystemHandle,FileSystemWritableFileStream:WritableStream," +
    "FileSystemObserver::1,FontData,FragmentDirective,HID:EventTarget,HIDConnectionEvent:Event:2," +
    "HIDDevice:EventTarget,HIDInputReportEvent:Event,IdentityCredential:Credential," +
    "IdentityCredentialError:DOMException,IdentityProvider,NavigatorLogin,LanguageDetector," +
    "LanguageModel:EventTarget,Lock,LockManager,ServiceWorker:EventTarget," +
    "ServiceWorkerContainer:EventTarget,OTPCredential:Credential,PaymentAddress," +
    "PaymentRequest:EventTarget:1,PaymentRequestUpdateEvent:Event:1,PaymentResponse:EventTarget," +
    "PaymentManager,PaymentMethodChangeEvent:PaymentRequestUpdateEvent:1,Presentation," +
    "PresentationAvailability:EventTarget,PresentationConnection:EventTarget," +
    "PresentationConnectionAvailableEvent:Event:2,PresentationConnectionCloseEvent:Event:2," +
    "PresentationConnectionList:EventTarget,PresentationReceiver,PresentationRequest:EventTarget:1," +
    "PressureRecord,Serial:EventTarget,SerialPort:EventTarget,SpeechRecognitionPhrase::1," +
    "StorageBucket,StorageBucketManager,Summarizer,Translator,USB:EventTarget," +
    "USBAlternateInterface::2,USBConfiguration::2,USBConnectionEvent:Event:2,USBDevice," +
    "USBEndpoint::3,USBInTransferResult::1,USBInterface::2,USBIsochronousInTransferPacket::1," +
    "USBIsochronousInTransferResult::1,USBIsochronousOutTransferPacket::1," +
    "USBIsochronousOutTransferResult::1,USBOutTransferResult::1,WakeLock," +
    "WakeLockSentinel:EventTarget,XRAnchor,XRSpace:EventTarget,XRReferenceSpace:XRSpace," +
    "XRBoundedReferenceSpace:XRReferenceSpace,XRDepthInformation," +
    "XRCPUDepthInformation:XRDepthInformation,XRCamera,XRFrame,XRHand,XRHitTestResult," +
    "XRHitTestSource,XRInputSource,XRInputSourceArray,XRInputSourceEvent:Event:2," +
    "XRInputSourcesChangeEvent:Event:2,XRPose,XRJointPose:XRPose,XRJointSpace:XRSpace," +
    "XRLightEstimate,XRLightProbe:EventTarget,XRRay,XRReferenceSpaceEvent:Event:2,XRRenderState," +
    "XRRigidTransform,XRSession:EventTarget,XRSessionEvent:Event:2,XRSystem:EventTarget," +
    "XRTransientInputHitTestResult,XRTransientInputHitTestSource,XRView,XRViewerPose:XRPose," +
    "XRViewport,XRWebGLDepthInformation:XRDepthInformation,XRWebGLLayer:XRLayer:2," +
    "XRCompositionLayer:XRLayer,XRProjectionLayer:XRCompositionLayer,XRCubeLayer:XRCompositionLayer," +
    "XRCylinderLayer:XRCompositionLayer,XREquirectLayer:XRCompositionLayer,XRLayerEvent:Event:2," +
    "XRQuadLayer:XRCompositionLayer,XRSubImage,XRWebGLSubImage:XRSubImage,XRPlane,XRPlaneSet," +
    "XRVisibilityMaskChangeEvent:Event:2,AnimationTrigger,BackgroundFetchManager," +
    "BackgroundFetchRecord,BackgroundFetchRegistration:EventTarget,BluetoothUUID," +
    "CSSFontFeatureValuesRule:CSSRule,CSSFunctionDeclarations:CSSRule," +
    "CSSFunctionDescriptors:CSSStyleDeclaration,CSSFunctionRule:CSSGroupingRule,CSSPseudoElement," +
    "ChapterInformation,CropTarget,DocumentPictureInPictureEvent:Event:2,Fence,FencedFrameConfig," +
    "HTMLCameraElement:HTMLElement,HTMLMicrophoneElement:HTMLElement," +
    "HTMLGeolocationElement:HTMLElement,HTMLUserMediaElement:HTMLElement," +
    "IntegrityViolationReportBody:ReportBody,LaunchParams,LaunchQueue,MediaMetadata,MediaSession," +
    "NodeRange:AbstractRange,OpaqueRange:AbstractRange," +
    "PerformanceLongAnimationFrameTiming:PerformanceEntry,PerformanceScriptTiming:PerformanceEntry," +
    "RTCDataChannel:EventTarget,PeriodicSyncManager,Profiler:EventTarget:1,PushManager," +
    "PushSubscription,PushSubscriptionOptions,QuotaExceededError:DOMException," +
    "RTCRtpScriptTransform::1,RemotePlayback:EventTarget,RestrictionTarget,SnapEvent:Event," +
    "SpeechGrammar,SpeechGrammarList,SpeechRecognitionErrorEvent:Event:1," +
    "SpeechRecognitionEvent:Event:1,SpeechSynthesisVoice,TimelineTrigger:AnimationTrigger," +
    "TimelineTriggerRange,TimelineTriggerRangeList,Viewport,WebSocketError:DOMException," +
    "WebSocketStream::1";
  var NOT_CHROME = [
    "InternalError",
    "SharedArrayBuffer",
    "AudioTrack",
    "AudioTrackList",
    "VideoTrack",
    "VideoTrackList",
    "CSSPageDescriptors",
    "CSSStyleProperties",
  ];
  var rows = CHROME.split(",");
  var i, parts, parent, ctor, proto;
  for (i = 0; i < rows.length; i++) {
    parts = rows[i].split(":");
    if (globalThis[parts[0]] !== undefined) continue;
    parent = parts[1] ? globalThis[parts[1]] : null;
    ctor = __nativeFn(
      parts[0],
      function () {
        throw new TypeError("Illegal constructor");
      },
      parts[2] ? +parts[2] : 0,
    );
    proto = Object.create(parent ? parent.prototype : Object.prototype);
    Object.defineProperty(proto, "constructor", {
      value: ctor,
      writable: true,
      configurable: true,
    });
    Object.defineProperty(proto, Symbol.toStringTag, { value: parts[0], configurable: true });
    Object.defineProperty(ctor, "prototype", { value: proto });
    if (parent) Object.setPrototypeOf(ctor, parent);
    Object.defineProperty(globalThis, parts[0], {
      value: ctor,
      writable: true,
      configurable: true,
    });
  }
  for (i = 0; i < NOT_CHROME.length; i++) delete globalThis[NOT_CHROME[i]];
})();
