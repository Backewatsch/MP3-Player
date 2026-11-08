/*
 * Handgeschriebene config.h für LAME 3.100 (iOS und Android, 32/64 Bit).
 * Ersetzt die von ./configure erzeugte Datei. Nur der Encoder wird gebaut (kein mpglib-Decoder,
 * kein NASM/SSE-Code).
 */
#ifndef LAME_CONFIG_H
#define LAME_CONFIG_H

#define STDC_HEADERS 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STRING_H 1
#define HAVE_STDLIB_H 1
#define HAVE_UNISTD_H 1
#define HAVE_STRCHR 1
#define HAVE_MEMCPY 1
#define PROTOTYPES 1
#define USE_FAST_LOG 1

#define SIZEOF_SHORT 2
#define SIZEOF_INT 4
#define SIZEOF_LONG __SIZEOF_LONG__
#define SIZEOF_UNSIGNED_SHORT 2
#define SIZEOF_UNSIGNED_INT 4
#define SIZEOF_UNSIGNED_LONG __SIZEOF_LONG__
#define SIZEOF_FLOAT 4
#define SIZEOF_DOUBLE 8
#define SIZEOF_LONG_DOUBLE __SIZEOF_LONG_DOUBLE__

#define PACKAGE "lame"
#define VERSION "3.100"
#define LAME_LIBRARY_BUILD

#include <stdint.h>
typedef float       ieee754_float32_t;
typedef double      ieee754_float64_t;
typedef long double ieee854_float80_t;

#endif /* LAME_CONFIG_H */
