#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <openssl/crypto.h>
#include <openssl/provider.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/opensslv.h>

#include "jniglue.h"

// Log all OpenSSL errors
static void log_openssl_errors(const char *context) {
    unsigned long err;
    while ((err = ERR_get_error()) != 0) {
        char buf[256];
        ERR_error_string_n(err, buf, sizeof(buf));
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "%s: OpenSSL error: %s", context, buf);
    }
}

// Callback for listing providers
static int list_providers(OSSL_PROVIDER *prov, void *data) {
    const char *name = OSSL_PROVIDER_get0_name(prov);
    int loaded = OSSL_PROVIDER_available(NULL, name);
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Provider: %s, Status: %s",
                        name, loaded ? "active" : "inactive");
    return 1; // Continue enumeration
}

// Callback for listing ciphers
static void list_ciphers(EVP_CIPHER *cipher, void *data) {
    const char *name = EVP_CIPHER_name(cipher);
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Cipher: %s", name);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Starting JNI_OnLoad");
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_2) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to get JNI environment");
        return JNI_ERR;
    }

    // Get nativeLibraryDir
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Retrieving nativeLibraryDir");
    jclass contextClass = (*env)->FindClass(env, "android/content/Context");
    if (!contextClass) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to find android/content/Context class");
        return JNI_VERSION_1_2;
    }
    jmethodID getApplicationInfo = (*env)->GetMethodID(env, contextClass, "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");
    if (!getApplicationInfo) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to get getApplicationInfo method");
        return JNI_VERSION_1_2;
    }
    jclass activityThreadClass = (*env)->FindClass(env, "android/app/ActivityThread");
    if (!activityThreadClass) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to find android/app/ActivityThread class");
        return JNI_VERSION_1_2;
    }
    jmethodID currentActivityThread = (*env)->GetStaticMethodID(env, activityThreadClass, "currentActivityThread", "()Landroid/app/ActivityThread;");
    if (!currentActivityThread) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to get currentActivityThread method");
        return JNI_VERSION_1_2;
    }
    jmethodID getApplication = (*env)->GetMethodID(env, activityThreadClass, "getApplication", "()Landroid/app/Application;");
    if (!getApplication) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to get getApplication method");
        return JNI_VERSION_1_2;
    }
    jobject activityThread = (*env)->CallStaticObjectMethod(env, activityThreadClass, currentActivityThread);
    if (!activityThread) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to get ActivityThread instance");
        return JNI_VERSION_1_2;
    }
    jobject application = (*env)->CallObjectMethod(env, activityThread, getApplication);
    if (!application) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to get Application instance");
        return JNI_VERSION_1_2;
    }
    jobject appInfo = (*env)->CallObjectMethod(env, application, getApplicationInfo);
    if (!appInfo) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to get ApplicationInfo");
        return JNI_VERSION_1_2;
    }
    jclass appInfoClass = (*env)->FindClass(env, "android/content/pm/ApplicationInfo");
    if (!appInfoClass) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to find android/content/pm/ApplicationInfo class");
        return JNI_VERSION_1_2;
    }
    jfieldID nativeLibraryDirField = (*env)->GetFieldID(env, appInfoClass, "nativeLibraryDir", "Ljava/lang/String;");
    if (!nativeLibraryDirField) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to get nativeLibraryDir field");
        return JNI_VERSION_1_2;
    }
    jstring nativeLibraryDir = (jstring)(*env)->GetObjectField(env, appInfo, nativeLibraryDirField);
    if (!nativeLibraryDir) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to get nativeLibraryDir value");
        return JNI_VERSION_1_2;
    }
    const char *libDir = (*env)->GetStringUTFChars(env, nativeLibraryDir, NULL);
    if (!libDir) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to convert nativeLibraryDir to C string");
        return JNI_VERSION_1_2;
    }
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Native library directory: %s", libDir);

    // Path to gostprov.so
    char providerPath[512];
    snprintf(providerPath, sizeof(providerPath), "%s/gostprov.so", libDir);
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Provider path: %s", providerPath);

    // Check if gostprov.so is accessible via dlopen
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Checking gostprov.so accessibility");
    void *handle = dlopen(providerPath, RTLD_LAZY);
    if (!handle) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to dlopen gostprov.so: %s", dlerror());
    } else {
        __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Successfully opened gostprov.so");
        dlclose(handle);
    }

    // Path to openssl.cnf
    char confPath[512];
    snprintf(confPath, sizeof(confPath), "/data/user/0/de.blinkt.openvpn/files/openssl.cnf");
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Attempting to create openssl.cnf at %s", confPath);

    // Create openssl.cnf on-the-fly
    char configContent[1024];
    snprintf(configContent, sizeof(configContent),
             "openssl_conf = openssl_init\n"
             "\n"
             "[openssl_init]\n"
             "providers = provider_sect\n"
             "\n"
             "[provider_sect]\n"
             "default = default_sect\n"
             "gostprov = gostprov_sect\n"
             "\n"
             "[default_sect]\n"
             "activate = 1\n"
             "\n"
             "[gostprov_sect]\n"
             "module = %s/gostprov.so\n"
             "activate = 1\n",
             libDir);
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "openssl.cnf content to write: %s", configContent);

    // Check and create directory
    char confDir[512];
    snprintf(confDir, sizeof(confDir), "/data/user/0/de.blinkt.openvpn/files");
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Checking directory %s", confDir);
    if (access(confDir, F_OK) != 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Directory %s does not exist, creating", confDir);
        if (mkdir(confDir, 0755) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to create directory %s: %s", confDir, strerror(errno));
        } else {
            __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Successfully created directory %s", confDir);
        }
    } else {
        __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Directory %s exists", confDir);
    }

    // Write openssl.cnf
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Writing openssl.cnf");
    FILE *confFile = fopen(confPath, "w");
    if (!confFile) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to open openssl.cnf for writing at %s: %s", confPath, strerror(errno));
    } else {
        size_t bytesWritten = fwrite(configContent, 1, strlen(configContent), confFile);
        fclose(confFile);
        if (bytesWritten != strlen(configContent)) {
            __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to write complete openssl.cnf content, wrote %zu bytes", bytesWritten);
        } else {
            __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Successfully wrote %zu bytes to openssl.cnf at %s", bytesWritten, confPath);
        }
    }

    // Set permissions on openssl.cnf
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Setting permissions on %s", confPath);
    if (chmod(confPath, 0644) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to set permissions on %s: %s", confPath, strerror(errno));
    } else {
        __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Set permissions on %s to 0644", confPath);
    }

    // Read back openssl.cnf for verification
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Reading openssl.cnf for verification");
    confFile = fopen(confPath, "r");
    char confContentRead[1024] = {0};
    if (!confFile) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to open openssl.cnf for reading at %s: %s", confPath, strerror(errno));
    } else {
        size_t bytesRead = fread(confContentRead, 1, sizeof(confContentRead) - 1, confFile);
        fclose(confFile);
        if (bytesRead > 0) {
            confContentRead[bytesRead] = '\0';
            __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Read openssl.cnf content: %s", confContentRead);
        } else {
            __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to read content from openssl.cnf: %s", strerror(errno));
        }
    }

    // Set OPENSSL_CONF
    char opensslConf[512];
    snprintf(opensslConf, sizeof(opensslConf), "OPENSSL_CONF=%s", confPath);
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Setting OPENSSL_CONF to: %s", opensslConf);
    if (putenv(opensslConf) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "openvpn", "Failed to set OPENSSL_CONF: %s", strerror(errno));
    } else {
        __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "OPENSSL_CONF set to: %s", getenv("OPENSSL_CONF"));
    }

    (*env)->ReleaseStringUTFChars(env, nativeLibraryDir, libDir);
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Completed JNI_OnLoad");
    return JNI_VERSION_1_2;
}

void android_openvpn_log(int level, const char* prefix, const char* prefix_sep, const char* m1) {
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "%s%s%s", prefix, prefix_sep, m1);
}

jstring Java_de_blinkt_openvpn_core_NativeUtils_getJNIAPI(JNIEnv *env, jclass jo) {
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Returning JNI API: %s", TARGET_ARCH_ABI);
    return (*env)->NewStringUTF(env, TARGET_ARCH_ABI);
}

jstring Java_de_blinkt_openvpn_core_NativeUtils_getOpenVPN2GitVersion(JNIEnv *env, jclass jo) {
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Returning OpenVPN2 Git Version: %s", OPENVPN2_GIT_REVISION);
    return (*env)->NewStringUTF(env, OPENVPN2_GIT_REVISION);
}

jstring Java_de_blinkt_openvpn_core_NativeUtils_getOpenVPN3GitVersion(JNIEnv *env, jclass jo) {
    __android_log_print(ANDROID_LOG_DEBUG, "openvpn", "Returning OpenVPN3 Git Version: %s", OPENVPN3_GIT_REVISION);
    return (*env)->NewStringUTF(env, OPENVPN3_GIT_REVISION);
}
