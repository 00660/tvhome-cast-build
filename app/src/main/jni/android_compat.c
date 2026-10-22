/* Android 4.4 (API19) libc lacks the FORTIFY *_chk socket helpers that the API21 headers
 * (used to compile this .so) route FD_SET/FD_CLR/FD_ISSET through. Provide them here so the
 * loader resolves the references on 4.4. Signatures match bionic (int, fd_set*, size_t). */
#include <sys/select.h>
#include <stddef.h>

#define FD_BITS_PER_SLOT (8 * sizeof(unsigned long))

void __FD_SET_chk(int fd, fd_set *set, size_t set_size) {
    (void) set_size;
    if (!set || fd < 0 || fd >= FD_SETSIZE) return;
    unsigned long *bits = (unsigned long *) set->fds_bits;
    bits[fd / FD_BITS_PER_SLOT] |= (1UL << (fd % FD_BITS_PER_SLOT));
}

void __FD_CLR_chk(int fd, fd_set *set, size_t set_size) {
    (void) set_size;
    if (!set || fd < 0 || fd >= FD_SETSIZE) return;
    unsigned long *bits = (unsigned long *) set->fds_bits;
    bits[fd / FD_BITS_PER_SLOT] &= ~(1UL << (fd % FD_BITS_PER_SLOT));
}

int __FD_ISSET_chk(int fd, fd_set *set, size_t set_size) {
    (void) set_size;
    if (!set || fd < 0 || fd >= FD_SETSIZE) return 0;
    unsigned long *bits = (unsigned long *) set->fds_bits;
    return (bits[fd / FD_BITS_PER_SLOT] & (1UL << (fd % FD_BITS_PER_SLOT))) != 0;
}
