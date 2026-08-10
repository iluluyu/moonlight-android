# Builds libevdev_grab.so — a tiny JNI helper used by the Shizuku UserService to
# exclusively grab keyboard event devices. Built for ALL flavors (not gated to
# root), so the non-root Shizuku backend can suppress OEM key interception.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := evdev_grab
LOCAL_SRC_FILES := evdev_grab.c
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)
