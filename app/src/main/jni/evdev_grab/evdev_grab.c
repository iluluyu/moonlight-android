// Minimal JNI helper for the Shizuku UserService: open an input device, check that
// it is a keyboard (has EV_KEY, lacks the EV_ABS axes that touchscreens/trackpads
// use), and exclusively grab it. After EVIOCGRAB the kernel stops delivering the
// device's events to the Android input framework, so OEM system shortcuts (e.g.
// OPPO's Win -> AI) no longer fire during streaming while the host still receives
// the key via our reader. Closing the returned fd releases the grab.
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <string.h>

JNIEXPORT jint JNICALL
Java_com_limelight_binding_input_shizuku_ShizukuInputService_nativeOpenKeyboardGrabbed(
        JNIEnv* env, jclass clazz, jstring jpath) {
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) {
        return -1;
    }
    int fd = open(path, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) {
        return -1;
    }

    unsigned char evbits[8];
    memset(evbits, 0, sizeof(evbits));
    if (ioctl(fd, EVIOCGBIT(0, sizeof(evbits)), evbits) < 0) {
        close(fd);
        return -1;
    }

    int hasKey = evbits[EV_KEY / 8] & (1 << (EV_KEY % 8));
    int hasAbs = evbits[EV_ABS / 8] & (1 << (EV_ABS % 8));

    // Keep keyboards/keypads; skip touchscreens/trackpads (EV_ABS) and others.
    if (!hasKey || hasAbs) {
        close(fd);
        return -1;
    }

    // Exclusive grab. If it fails (EBUSY) we still return the fd for reading.
    ioctl(fd, EVIOCGRAB, 1);

    return fd;
}
