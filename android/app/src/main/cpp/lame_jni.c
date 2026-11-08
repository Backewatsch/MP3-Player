/*
 * JNI-Brücke zum LAME-MP3-Encoder (com.backewatsch.mp3player.convert.LameNative).
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "lame.h"

#define FN(name) Java_com_backewatsch_mp3player_convert_LameNative_##name

JNIEXPORT jlong JNICALL
FN(nativeInit)(JNIEnv *env, jclass cls, jint inSampleRate, jint channels, jint bitrate, jint quality) {
    lame_t lame = lame_init();
    if (!lame) return 0;
    lame_set_in_samplerate(lame, inSampleRate);
    lame_set_num_channels(lame, channels);
    lame_set_out_samplerate(lame, 44100);
    lame_set_VBR(lame, vbr_off);
    lame_set_brate(lame, bitrate);
    lame_set_quality(lame, quality);
    if (channels == 1) lame_set_mode(lame, MONO);
    id3tag_init(lame);
    id3tag_add_v2(lame);
    id3tag_v2_only(lame);
    return (jlong) (intptr_t) lame;
}

/* Setzt einen ID3v2-Textrahmen als UTF-16 mit BOM (Java-Strings sind bereits UTF-16). */
static void set_text(JNIEnv *env, lame_t lame, const char *frame, jstring value) {
    if (!value) return;
    jsize len = (*env)->GetStringLength(env, value);
    if (len <= 0) return;
    unsigned short *text = malloc(sizeof(unsigned short) * (len + 2));
    if (!text) return;
    text[0] = 0xFEFF;
    (*env)->GetStringRegion(env, value, 0, len, (jchar *) (text + 1));
    text[len + 1] = 0;
    id3tag_set_textinfo_utf16(lame, frame, text);
    free(text);
}

JNIEXPORT jint JNICALL
FN(nativeSetTags)(JNIEnv *env, jclass cls, jlong handle, jstring title, jstring artist, jstring album, jbyteArray artwork) {
    lame_t lame = (lame_t) (intptr_t) handle;
    set_text(env, lame, "TIT2", title);
    set_text(env, lame, "TPE1", artist);
    set_text(env, lame, "TALB", album);
    if (artwork) {
        jsize size = (*env)->GetArrayLength(env, artwork);
        jbyte *bytes = (*env)->GetByteArrayElements(env, artwork, NULL);
        if (bytes) {
            id3tag_set_albumart(lame, (const char *) bytes, (size_t) size);
            (*env)->ReleaseByteArrayElements(env, artwork, bytes, JNI_ABORT);
        }
    }
    return lame_init_params(lame);
}

JNIEXPORT jint JNICALL
FN(nativeEncode)(JNIEnv *env, jclass cls, jlong handle, jshortArray pcm, jint frames, jint channels, jbyteArray out) {
    lame_t lame = (lame_t) (intptr_t) handle;
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *buffer = (*env)->GetByteArrayElements(env, out, NULL);
    jsize capacity = (*env)->GetArrayLength(env, out);
    int written;
    if (channels == 1) {
        written = lame_encode_buffer(lame, samples, samples, frames, (unsigned char *) buffer, capacity);
    } else {
        written = lame_encode_buffer_interleaved(lame, samples, frames, (unsigned char *) buffer, capacity);
    }
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, buffer, 0);
    return written;
}

JNIEXPORT jint JNICALL
FN(nativeFlush)(JNIEnv *env, jclass cls, jlong handle, jbyteArray out) {
    lame_t lame = (lame_t) (intptr_t) handle;
    jbyte *buffer = (*env)->GetByteArrayElements(env, out, NULL);
    jsize capacity = (*env)->GetArrayLength(env, out);
    int written = lame_encode_flush(lame, (unsigned char *) buffer, capacity);
    (*env)->ReleaseByteArrayElements(env, out, buffer, 0);
    return written;
}

JNIEXPORT void JNICALL
FN(nativeClose)(JNIEnv *env, jclass cls, jlong handle) {
    lame_t lame = (lame_t) (intptr_t) handle;
    if (lame) lame_close(lame);
}
