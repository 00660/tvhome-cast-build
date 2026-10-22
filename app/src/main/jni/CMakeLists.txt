cmake_minimum_required(VERSION 3.18)
project(airplay_jni C)
set(OPENSSL_DIR ${CMAKE_SOURCE_DIR}/openssl/${ANDROID_ABI})

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -fPIC -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 -Wno-error")

# ---------- libplist (static) ----------
file(GLOB PLIST_SRC
    ${CMAKE_SOURCE_DIR}/libplist/src/*.c
    ${CMAKE_SOURCE_DIR}/libplist/libcnary/*.c)
add_library(plist STATIC ${PLIST_SRC})
target_include_directories(plist PUBLIC
    ${CMAKE_SOURCE_DIR}/libplist/include
    ${CMAKE_SOURCE_DIR}/libplist/src
    ${CMAKE_SOURCE_DIR}/libplist/libcnary
    ${CMAKE_SOURCE_DIR}/libplist/libcnary/include)
target_compile_definitions(plist PRIVATE
    HAVE_STRNDUP=1 HAVE_MEMMEM=1 HAVE_GMTIME_R=1 HAVE_LOCALTIME_R=1
    HAVE_STRPTIME=1 HAVE_TIMEGM=1 HAVE_TM_TM_GMTOFF=1 HAVE_TM_TM_ZONE=1
    PACKAGE_VERSION="2.3.0" PLIST_230=1)

# ---------- playfair (fairplay crypto) ----------
file(GLOB PLAYFAIR_SRC ${CMAKE_SOURCE_DIR}/uxplay-lib/playfair/*.c)
add_library(playfair STATIC ${PLAYFAIR_SRC})
target_include_directories(playfair PUBLIC ${CMAKE_SOURCE_DIR}/uxplay-lib/playfair)

# ---------- llhttp ----------
file(GLOB LLHTTP_SRC ${CMAKE_SOURCE_DIR}/uxplay-lib/llhttp/*.c)
add_library(llhttp STATIC ${LLHTTP_SRC})
target_include_directories(llhttp PUBLIC ${CMAKE_SOURCE_DIR}/uxplay-lib/llhttp)

# ---------- dnssd (core + mdnsd backend) ----------
add_library(dnssd STATIC
    ${CMAKE_SOURCE_DIR}/uxplay-lib/dnssd.c
    ${CMAKE_SOURCE_DIR}/uxplay-lib/mdnsd/dnssd_mdnsd.c
    ${CMAKE_SOURCE_DIR}/uxplay-lib/mdnsd/mdnsd.c)
target_include_directories(dnssd PUBLIC ${CMAKE_SOURCE_DIR}/uxplay-lib ${CMAKE_SOURCE_DIR}/uxplay-lib/mdnsd)

# ---------- uxplay airplay core (all lib/*.c except dnssd.c, which is in dnssd target) ----------
file(GLOB UXPLAY_SRC ${CMAKE_SOURCE_DIR}/uxplay-lib/*.c)
list(REMOVE_ITEM UXPLAY_SRC ${CMAKE_SOURCE_DIR}/uxplay-lib/dnssd.c)
add_library(airplay STATIC ${UXPLAY_SRC})
target_include_directories(airplay PUBLIC
    ${CMAKE_SOURCE_DIR}/uxplay-lib
    ${CMAKE_SOURCE_DIR}/uxplay-lib/playfair
    ${CMAKE_SOURCE_DIR}/uxplay-lib/llhttp
    ${CMAKE_SOURCE_DIR}/libplist/include
    ${CMAKE_SOURCE_DIR}/libplist/src
    ${OPENSSL_DIR}/include)
target_compile_definitions(airplay PUBLIC
    -DNOHOLD -DSTANDALONE -DPLIST_230=1 -DOPENSSL_API_COMPAT=0x10101000L)
target_link_libraries(airplay PUBLIC plist playfair dnssd llhttp)

# ---------- JNI bridge (final shared lib) ----------
add_library(airplay_jni SHARED airplay_jni.c)
message(STATUS "OPENSSL_DIR = ${OPENSSL_DIR}")
file(GLOB CRYPTO_LIB ${OPENSSL_DIR}/lib/libcrypto.a)
file(GLOB SSL_LIB ${OPENSSL_DIR}/lib/libssl.a)
message(STATUS "CRYPTO_LIB = ${CRYPTO_LIB}")
message(STATUS "SSL_LIB = ${SSL_LIB}")
target_include_directories(airplay_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/uxplay-lib
    ${CMAKE_SOURCE_DIR}/uxplay-lib/playfair
    ${CMAKE_SOURCE_DIR}/uxplay-lib/llhttp
    ${CMAKE_SOURCE_DIR}/libplist/include
    ${OPENSSL_DIR}/include)
target_link_libraries(airplay_jni
    airplay plist playfair dnssd llhttp
    ${CRYPTO_LIB} ${SSL_LIB}
    log android)
