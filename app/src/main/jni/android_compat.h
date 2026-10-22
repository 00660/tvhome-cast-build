#ifndef ANDROID_COMPAT_H
#define ANDROID_COMPAT_H
/* This .so is compiled against API21 headers (NDK minimum) but must run on Android 4.4
 * (API19). Two libc symbols that newer bionic/headers emit are missing on 4.4:
 *   - strtof        (added in API21)  -> map to (float)strtod, which exists on 4.4
 *   - __*_chk2      (FORTIFY_SOURCE=2 variants) -> removed by disabling FORTIFY in CMake
 * Defining strtof as a macro here removes the reference entirely, so the loader never
 * looks for the missing symbol. */
#include <stdlib.h>
#ifndef strtof
#define strtof(nptr, endptr) ((float) strtod((nptr), (endptr)))
#endif
#endif
