// Preloaded by .jitpack/build.sh only on a JitPack builder whose java cannot load Maven's launcher.
//
// Java 22 and later read file attributes with the statx syscall. Some JitPack builders run kernel 4.14 under a
// container seccomp profile that predates statx and refuses it with EPERM rather than ENOSYS, so glibc does not
// fall back, every stat java makes fails, and a jar on the classpath is skipped as unreadable:
// `Could not find or load main class org.codehaus.plexus.classworlds.launcher.Launcher`. basics v0.3.0, v0.3.1
// and v0.3.3 were lost that way on 2026-10-10. This answers statx from fstatat, which those builders allow.
#define _GNU_SOURCE
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>

static void copy_time(struct statx_timestamp *to, const struct timespec *from) {
    to->tv_sec = from->tv_sec;
    to->tv_nsec = (__u32) from->tv_nsec;
}

int statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *out) {
    (void) mask;
    struct stat st;
    if (fstatat(dirfd, path, &st, flags & (AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH | AT_NO_AUTOMOUNT)) != 0) {
        return -1;
    }
    memset(out, 0, sizeof *out);
    out->stx_mask = STATX_BASIC_STATS;
    out->stx_blksize = (__u32) st.st_blksize;
    out->stx_nlink = (__u32) st.st_nlink;
    out->stx_uid = st.st_uid;
    out->stx_gid = st.st_gid;
    out->stx_mode = (__u16) st.st_mode;
    out->stx_ino = st.st_ino;
    out->stx_size = (__u64) st.st_size;
    out->stx_blocks = (__u64) st.st_blocks;
    copy_time(&out->stx_atime, &st.st_atim);
    copy_time(&out->stx_mtime, &st.st_mtim);
    copy_time(&out->stx_ctime, &st.st_ctim);
    out->stx_rdev_major = major(st.st_rdev);
    out->stx_rdev_minor = minor(st.st_rdev);
    out->stx_dev_major = major(st.st_dev);
    out->stx_dev_minor = minor(st.st_dev);
    return 0;
}
