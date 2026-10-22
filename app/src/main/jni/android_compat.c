/* Android 4.4 (API19) libc lacks several symbols that the API21 headers (used to compile this
 * .so) and OpenSSL/libplist reference. Provide them here so the loader resolves everything on 4.4. */
#define _GNU_SOURCE
#include <sys/select.h>
#include <sys/socket.h>
#include <signal.h>
#include <stddef.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>

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
int __FD_ISSET_chk(int fd, const fd_set *set, size_t set_size) {
    (void) set_size;
    if (!set || fd < 0 || fd >= FD_SETSIZE) return 0;
    const unsigned long *bits = (const unsigned long *) set->fds_bits;
    return (bits[fd / FD_BITS_PER_SLOT] & (1UL << (fd % FD_BITS_PER_SLOT))) != 0;
}

/* CMSG_NXTHDR (added API21) — bionic implementation. */
struct cmsghdr *__cmsg_nxthdr(struct msghdr *msg, struct cmsghdr *cmsg) {
    struct cmsghdr *ptr;
    ptr = (struct cmsghdr *) (((unsigned char *) cmsg) + CMSG_ALIGN(cmsg->cmsg_len));
    if ((unsigned char *) (ptr + 1) > (unsigned char *) msg->msg_control + msg->msg_controllen)
        return NULL;
    return ptr;
}

/* getentropy (added API28) — fill from /dev/urandom. */
int getentropy(void *buffer, size_t length) {
    if (length > 256) { errno = EIO; return -1; }
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    size_t done = 0;
    while (done < length) {
        ssize_t n = read(fd, (char *) buffer + done, length - done);
        if (n <= 0) { close(fd); errno = EIO; return -1; }
        done += (size_t) n;
    }
    close(fd);
    return 0;
}

/* signal() and sigfillset()/sigdelset() are exported by API21 bionic but not 4.4. */
sighandler_t signal(int signum, sighandler_t handler) {
    return bsd_signal(signum, handler);
}
int sigfillset(sigset_t *set) {
    if (!set) { errno = EINVAL; return -1; }
    memset(set, 0xff, sizeof(*set));
    /* SIGKILL and SIGSTOP cannot be caught/blocked; bionic clears them. */
    unsigned long *bits = (unsigned long *) set;
    int kill_bit = SIGKILL - 1, stop_bit = SIGSTOP - 1;
    bits[kill_bit / (8 * sizeof(unsigned long))] &= ~(1UL << (kill_bit % (8 * sizeof(unsigned long))));
    bits[stop_bit / (8 * sizeof(unsigned long))] &= ~(1UL << (stop_bit % (8 * sizeof(unsigned long))));
    return 0;
}
int sigdelset(sigset_t *set, int signum) {
    if (!set || signum < 1 || signum > 64) { errno = EINVAL; return -1; }
    unsigned long *bits = (unsigned long *) set;
    int b = signum - 1;
    bits[b / (8 * sizeof(unsigned long))] &= ~(1UL << (b % (8 * sizeof(unsigned long))));
    return 0;
}
