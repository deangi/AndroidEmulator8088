#include <jni.h>
#include "core/portable_z80.h"
#include "pdp11/android_pdp11.h"

namespace {
jbyteArray asByteArray(JNIEnv* env, const std::vector<uint8_t>& bytes) {
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (!bytes.empty())
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                                reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeReplaceDisks(JNIEnv* env, jobject, jlong handle, jintArray descriptors, jint hddMask) {
    if (env->GetArrayLength(descriptors) != 4) return false;
    jint fds[4]; env->GetIntArrayRegion(descriptors, 0, 4, fds);
    return reinterpret_cast<vz80::PortableZ80*>(handle)->replaceDisks({fds[0], fds[1], fds[2], fds[3]}, static_cast<uint8_t>(hddMask));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeTakeDiskError(JNIEnv* env, jobject, jlong handle) {
    const auto error = reinterpret_cast<vz80::PortableZ80*>(handle)->takeDiskError();
    return env->NewStringUTF(error.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeBootDisks(JNIEnv* env, jobject, jlong handle, jintArray descriptors, jint hddMask, jint bootDrive) {
    if (env->GetArrayLength(descriptors) != 4) return false;
    jint fds[4]; env->GetIntArrayRegion(descriptors, 0, 4, fds);
    return reinterpret_cast<vz80::PortableZ80*>(handle)->bootDisks({fds[0], fds[1], fds[2], fds[3]}, static_cast<uint8_t>(hddMask), bootDrive);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new vz80::PortableZ80());
}

extern "C" JNIEXPORT void JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<vz80::PortableZ80*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeReset(JNIEnv*, jobject, jlong handle, jint pc) {
    reinterpret_cast<vz80::PortableZ80*>(handle)->reset(static_cast<uint16_t>(pc));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeRunCycles(JNIEnv*, jobject, jlong handle, jlong cycles) {
    return static_cast<jlong>(reinterpret_cast<vz80::PortableZ80*>(handle)->runCycles(static_cast<unsigned long>(cycles)));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeRunInstructions(JNIEnv*, jobject, jlong handle, jlong instructions) {
    return static_cast<jlong>(reinterpret_cast<vz80::PortableZ80*>(handle)->runInstructions(static_cast<unsigned long>(instructions)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeLoad(JNIEnv* env, jobject, jlong handle, jint address, jbyteArray bytes) {
    const auto length = env->GetArrayLength(bytes);
    auto* data = env->GetByteArrayElements(bytes, nullptr);
    reinterpret_cast<vz80::PortableZ80*>(handle)->load(
        static_cast<uint16_t>(address), reinterpret_cast<uint8_t*>(data), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeEnqueueInput(JNIEnv*, jobject, jlong handle, jbyte value) {
    reinterpret_cast<vz80::PortableZ80*>(handle)->enqueueInput(static_cast<uint8_t>(value));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeHasPendingConsoleInput(JNIEnv*, jobject, jlong handle) {
    return reinterpret_cast<vz80::PortableZ80*>(handle)->hasPendingConsoleInput();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeDrainConsole(JNIEnv* env, jobject, jlong handle) {
    const auto bytes = reinterpret_cast<vz80::PortableZ80*>(handle)->drainConsole();
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_deangi_androidpdp11_NativeEmulator_nativeDrainPrinter(JNIEnv* env, jobject, jlong handle) {
    const auto bytes = reinterpret_cast<vz80::PortableZ80*>(handle)->drainPrinter();
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_deangi_androidpdp11_Pdp11NativeCore_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new pdp11android::Engine());
}

extern "C" JNIEXPORT void JNICALL
Java_com_deangi_androidpdp11_Pdp11NativeCore_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<pdp11android::Engine*>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_deangi_androidpdp11_Pdp11NativeCore_nativeProcessorId(JNIEnv* env, jobject, jlong handle) {
    return env->NewStringUTF(reinterpret_cast<pdp11android::Engine*>(handle)->processorId());
}

extern "C" JNIEXPORT void JNICALL
Java_com_deangi_androidpdp11_Pdp11NativeCore_nativeReset(JNIEnv*, jobject, jlong handle, jint pc) {
    reinterpret_cast<pdp11android::Engine*>(handle)->reset(static_cast<uint16_t>(pc));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_deangi_androidpdp11_Pdp11NativeCore_nativeRunInstructions(JNIEnv*, jobject, jlong handle, jlong count) {
    return static_cast<jlong>(reinterpret_cast<pdp11android::Engine*>(handle)->runInstructions(static_cast<uint64_t>(count)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_deangi_androidpdp11_Pdp11NativeCore_nativeMemorySizeBytes(JNIEnv*, jobject, jlong handle) {
    return static_cast<jint>(reinterpret_cast<pdp11android::Engine*>(handle)->memorySizeBytes());
}

extern "C" JNIEXPORT void JNICALL
Java_com_deangi_androidpdp11_Pdp11NativeCore_nativeSetMemorySizeBytes(JNIEnv*, jobject, jlong handle, jint bytes) {
    reinterpret_cast<pdp11android::Engine*>(handle)->setMemorySizeBytes(static_cast<uint32_t>(bytes));
}

extern "C" JNIEXPORT void JNICALL
Java_com_deangi_androidpdp11_Pdp11NativeCore_nativeClearMemory(JNIEnv*, jobject, jlong handle, jbyte value) {
    reinterpret_cast<pdp11android::Engine*>(handle)->clearMemory(static_cast<uint8_t>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_com_deangi_androidpdp11_Pdp11NativeCore_nativeLoad(JNIEnv* env, jobject, jlong handle, jint address, jbyteArray bytes) {
    const auto size = env->GetArrayLength(bytes);
    auto* data = env->GetByteArrayElements(bytes, nullptr);
    reinterpret_cast<pdp11android::Engine*>(handle)->load(static_cast<uint32_t>(address),
        reinterpret_cast<const uint8_t*>(data), static_cast<size_t>(size));
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_deangi_androidpdp11_Pdp11NativeCore_nativeEnqueueInput(JNIEnv*, jobject, jlong handle, jbyte value) {
    reinterpret_cast<pdp11android::Engine*>(handle)->enqueueInput(static_cast<uint8_t>(value));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deangi_androidpdp11_Pdp11NativeCore_nativeHasPendingConsoleInput(JNIEnv*, jobject, jlong handle) {
    return reinterpret_cast<pdp11android::Engine*>(handle)->hasPendingInput();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_deangi_androidpdp11_Pdp11NativeCore_nativeDrainConsole(JNIEnv* env, jobject, jlong handle) {
    return asByteArray(env, reinterpret_cast<pdp11android::Engine*>(handle)->drainConsole());
}
