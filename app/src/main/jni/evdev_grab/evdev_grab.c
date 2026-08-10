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

// Open a touchpad/trackpad device for OBSERVATION (no grab — the trackpad must keep
// driving the Android cursor). Used to read ABS_MT multitouch directly so we can
// detect 3+ finger gestures that ColorOS otherwise consumes at the framework level.
JNIEXPORT jint JNICALL
Java_com_limelight_binding_input_shizuku_ShizukuInputService_nativeOpenTrackpadObserver(
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
    int hasAbs = evbits[EV_ABS / 8] & (1 << (EV_ABS % 8));
    if (!hasAbs) {
        close(fd);
        return -1;
    }

    // Require multitouch (slot + position axes).
    unsigned char absbits[64];
    memset(absbits, 0, sizeof(absbits));
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits) < 0) {
        close(fd);
        return -1;
    }
    int hasMtSlot = absbits[ABS_MT_SLOT / 8] & (1 << (ABS_MT_SLOT % 8));
    int hasMtPos = absbits[ABS_MT_POSITION_X / 8] & (1 << (ABS_MT_POSITION_X % 8));
    if (!hasMtSlot || !hasMtPos) {
        close(fd);
        return -1;
    }

    // Device name should indicate a touchpad/trackpad (skip the main touchscreen).
    char name[256];
    memset(name, 0, sizeof(name));
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) {
        close(fd);
        return -1;
    }
    int isTrackpad = strstr(name, "touchpad") != NULL || strstr(name, "Touchpad") != NULL
            || strstr(name, "trackpad") != NULL || strstr(name, "Trackpad") != NULL;
    if (!isTrackpad) {
        close(fd);
        return -1;
    }

    // Intentionally NOT grabbing — the trackpad still controls the cursor.
    return fd;
}
