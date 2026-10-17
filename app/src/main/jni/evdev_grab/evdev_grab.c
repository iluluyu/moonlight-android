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

/* Multitouch touchpad support (Android 17 ColorOS no longer forwards raw
 * multi-finger frames to apps, so the app reads the pad node itself).
 *
 * Opens the node, verifies it exposes ABS_MT position axes, and grabs it
 * exclusively: after EVIOCGRAB the Android input framework stops receiving that
 * device, so the app must synthesize pointer/tap/scroll from these frames itself
 * (which the existing TrackpadContext pipeline already does). Closing the fd
 * releases the grab, so process death always restores system input. */
JNIEXPORT jint JNICALL
Java_com_limelight_binding_input_shizuku_ShizukuInputService_nativeOpenTouchpadGrabbed(
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

    unsigned char absbits[8];
    memset(absbits, 0, sizeof(absbits));
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits) < 0) {
        close(fd);
        return -1;
    }
    int hasMtX = absbits[ABS_MT_POSITION_X / 8] & (1 << (ABS_MT_POSITION_X % 8));
    int hasMtY = absbits[ABS_MT_POSITION_Y / 8] & (1 << (ABS_MT_POSITION_Y % 8));
    if (!hasMtX || !hasMtY) {
        close(fd);
        return -1;
    }

    // Only accept a real touchpad: INPUT_PROP_POINTER and/or INPUT_PROP_BUTTONPAD.
    // Reject INPUT_PROP_DIRECT, which is the panel itself - grabbing the digitizer
    // would kill on-screen touch for the whole system.
    unsigned long propbits = 0;
    if (ioctl(fd, EVIOCGPROP(sizeof(propbits)), &propbits) >= 0) {
        int isDirect = (propbits & (1UL << INPUT_PROP_DIRECT)) != 0;
        int isPointer = (propbits & (1UL << INPUT_PROP_POINTER)) != 0;
        int isButtonPad = (propbits & (1UL << INPUT_PROP_BUTTONPAD)) != 0;
        if (isDirect || (!isPointer && !isButtonPad)) {
            close(fd);
            return -1;
        }
    }

    /* A touchpad MUST be grabbed exclusively. Without the grab the framework keeps
     * receiving it, so the pointer/tap/scroll this app synthesizes from the frames
     * would be applied on top of the system's own handling (double input). Reading
     * read-only is therefore treated as failure. */
    if (ioctl(fd, EVIOCGRAB, 1) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* Returns {xMax, yMax, touchMajorMax} of the contact coordinate space, or NULL. */
JNIEXPORT jintArray JNICALL
Java_com_limelight_binding_input_shizuku_ShizukuInputService_nativeTouchpadAbsInfo(
        JNIEnv* env, jclass clazz, jint fd) {
    struct input_absinfo xi;
    struct input_absinfo yi;
    memset(&xi, 0, sizeof(xi));
    memset(&yi, 0, sizeof(yi));
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &xi) < 0 ||
            ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &yi) < 0 ||
            xi.maximum <= 0 || yi.maximum <= 0) {
        return NULL;
    }

    struct input_absinfo si;
    memset(&si, 0, sizeof(si));
    if (ioctl(fd, EVIOCGABS(ABS_MT_TOUCH_MAJOR), &si) < 0 || si.maximum <= 0) {
        si.maximum = 100; /* OPPO Pad reports 0..100; this is only a normalisation base */
    }

    jint values[3] = { xi.maximum, yi.maximum, si.maximum };
    jintArray out = (*env)->NewIntArray(env, 3);
    if (out) {
        (*env)->SetIntArrayRegion(env, out, 0, 3, values);
    }
    return out;
}
