package com.backewatsch.mp3player.convert

/** Direkter Zugriff auf den LAME-MP3-Encoder (C-Code in ThirdParty/lame, Brücke in cpp/lame_jni.c). */
object LameNative {
    init {
        System.loadLibrary("mp3lame")
    }

    @JvmStatic external fun nativeInit(inSampleRate: Int, channels: Int, bitrate: Int, quality: Int): Long
    @JvmStatic external fun nativeSetTags(handle: Long, title: String?, artist: String?, album: String?, artwork: ByteArray?): Int
    @JvmStatic external fun nativeEncode(handle: Long, pcm: ShortArray, frames: Int, channels: Int, out: ByteArray): Int
    @JvmStatic external fun nativeFlush(handle: Long, out: ByteArray): Int
    @JvmStatic external fun nativeClose(handle: Long)
}
