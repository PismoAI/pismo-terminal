LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := termexec
LOCAL_SRC_FILES := process.cpp
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
