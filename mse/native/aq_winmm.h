#ifndef AQ_WINMM_H
#define AQ_WINMM_H

/*
 * aq_pulse.h's AudioQueue + dispatch_after_f subset on Windows WinMM waveOut (a core system
 * library, nothing dlopen'd), so file_av.c's AudioQueue DAC path compiles unchanged on Windows.
 */

#include <stdbool.h>
#include <stdint.h>

typedef uint32_t UInt32;
typedef int32_t OSStatus;
typedef float Float32;
typedef double Float64;
typedef uint32_t AudioQueueParameterID;
typedef Float32 AudioQueueParameterValue;

typedef struct {
    Float64 mSampleRate;
    UInt32 mFormatID;
    UInt32 mFormatFlags;
    UInt32 mBytesPerPacket;
    UInt32 mFramesPerPacket;
    UInt32 mBytesPerFrame;
    UInt32 mChannelsPerFrame;
    UInt32 mBitsPerChannel;
    UInt32 mReserved;
} AudioStreamBasicDescription;

typedef struct AudioQueueBuffer {
    UInt32 mAudioDataBytesCapacity;
    void *mAudioData;
    UInt32 mAudioDataByteSize;
} AudioQueueBuffer;

typedef AudioQueueBuffer *AudioQueueBufferRef;
typedef struct OpaqueAudioQueue *AudioQueueRef;
typedef void (*AudioQueueOutputCallback)(void *user, AudioQueueRef q, AudioQueueBufferRef buf);

#define kAudioFormatLinearPCM 0x6C70636Du
#define kLinearPCMFormatFlagIsSignedInteger (1u << 2)
#define kLinearPCMFormatFlagIsPacked (1u << 3)
#define kAudioFormatFlagsNativeEndian 0u
#define kAudioQueueParam_Volume 1u
#define kAudioQueueParam_VolumeRampTime 4u
#define kCFRunLoopCommonModes NULL

OSStatus AudioQueueNewOutput(const AudioStreamBasicDescription *fmt, AudioQueueOutputCallback cb, void *user,
                             void *run_loop, const void *run_loop_mode, UInt32 flags, AudioQueueRef *out);
OSStatus AudioQueueAllocateBuffer(AudioQueueRef q, UInt32 bytes, AudioQueueBufferRef *out);
OSStatus AudioQueueEnqueueBuffer(AudioQueueRef q, AudioQueueBufferRef buf, UInt32 n_packet_descs,
                                 const void *packet_descs);
OSStatus AudioQueueStart(AudioQueueRef q, const void *start_time);
OSStatus AudioQueuePause(AudioQueueRef q);
/* Drops what is queued (buffers are kept, zeroed). */
OSStatus AudioQueueFlush(AudioQueueRef q);
OSStatus AudioQueueStop(AudioQueueRef q, bool immediate);
/* After return the callback never runs again. Never call it holding a lock the callback takes. */
OSStatus AudioQueueDispose(AudioQueueRef q, bool immediate);
OSStatus AudioQueueSetParameter(AudioQueueRef q, AudioQueueParameterID id, AudioQueueParameterValue v);

typedef uint64_t dispatch_time_t;
typedef void *dispatch_queue_t;
typedef void (*dispatch_function_t)(void *ctx);
#define DISPATCH_TIME_NOW 0ull
#define QOS_CLASS_USER_INITIATED 0

dispatch_time_t dispatch_time(dispatch_time_t when, int64_t delta_ns);
dispatch_queue_t dispatch_get_global_queue(long identifier, unsigned long flags);
/* One detached thread per call; only the pause fade uses it. */
void dispatch_after_f(dispatch_time_t when, dispatch_queue_t queue, void *ctx, dispatch_function_t fn);

#endif
