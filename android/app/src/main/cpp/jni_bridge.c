// 给 Kotlin 调用的 MP3 / OGG 编码器（底层和 iPhone 版共用 Codecs/AudioEncoders.c）
#include <jni.h>
#include <stdint.h>
#include "AudioEncoders.h"

#define FN(name) Java_com_example_videotool_NativeEncoders_##name

static int write_floats(JNIEnv *env, jfloatArray samples, jint frames, int channels,
                        int (*writer)(void *, const float *, int), void *enc) {
    jfloat *data = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (data == NULL) return -1;
    int result = writer(enc, data, frames);
    (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
    return result;
}

static int mp3_writer(void *enc, const float *data, int frames) {
    return mp3enc_write((MP3Encoder *)enc, data, frames);
}

static int ogg_writer(void *enc, const float *data, int frames) {
    return oggenc_write((OggEncoder *)enc, data, frames);
}

JNIEXPORT jlong JNICALL FN(mp3Open)(JNIEnv *env, jclass cls, jstring path, jint rate, jint channels, jint kbps) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    MP3Encoder *enc = mp3enc_open(p, rate, channels, kbps);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong)(intptr_t)enc;
}

JNIEXPORT jint JNICALL FN(mp3Write)(JNIEnv *env, jclass cls, jlong handle, jfloatArray samples, jint frames, jint channels) {
    return write_floats(env, samples, frames, channels, mp3_writer, (void *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL FN(mp3Finish)(JNIEnv *env, jclass cls, jlong handle) {
    return mp3enc_finish((MP3Encoder *)(intptr_t)handle);
}

JNIEXPORT void JNICALL FN(mp3Abort)(JNIEnv *env, jclass cls, jlong handle) {
    mp3enc_abort((MP3Encoder *)(intptr_t)handle);
}

JNIEXPORT jlong JNICALL FN(oggOpen)(JNIEnv *env, jclass cls, jstring path, jint rate, jint channels, jfloat quality) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    OggEncoder *enc = oggenc_open(p, rate, channels, quality);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong)(intptr_t)enc;
}

JNIEXPORT jint JNICALL FN(oggWrite)(JNIEnv *env, jclass cls, jlong handle, jfloatArray samples, jint frames, jint channels) {
    return write_floats(env, samples, frames, channels, ogg_writer, (void *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL FN(oggFinish)(JNIEnv *env, jclass cls, jlong handle) {
    return oggenc_finish((OggEncoder *)(intptr_t)handle);
}

JNIEXPORT void JNICALL FN(oggAbort)(JNIEnv *env, jclass cls, jlong handle) {
    oggenc_abort((OggEncoder *)(intptr_t)handle);
}
