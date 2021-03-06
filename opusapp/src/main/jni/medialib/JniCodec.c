/*
 * JniCodec.c
 *
 * Copyright (c) 2012, Philippe Chepy
 * All rights reserved.
 *
 * This software is the confidential and proprietary information
 * of Philippe Chepy.
 * You shall not disclose such Confidential Information.
 *
 * http://www.chepy.eu
 */

#include <jni.h>

#include <audio_engine/engine.h>
#include <audio_engine/utils/log.h>
#include <audio_engine/utils/memory.h>

#include <inttypes.h>

#define LOG_TAG "AudioEnginePlayback-JNI"

static JavaVMAttachArgs vmAttach;

static long ptr_to_id(void * ptr) {
	return (long) ptr;
}

static void * id_to_ptr(long id) {
	return (void *) id;
}

void playbackEndedCallback(engine_stream_context_s * stream) {
	JavaVM * vm = stream->engine->vm;
	jobject obj = stream->engine->obj;
	jclass cls  = stream->engine->cls;

    vmAttach.version = JNI_VERSION_1_6;  /* must be JNI_VERSION_1_2 */
    vmAttach.name = "JNICodec-Thread";    /* the name of the thread as a modified UTF-8 string, or NULL */
    vmAttach.group = NULL; /* global ref of a ThreadGroup object, or NULL */

	JNIEnv * env;
	int getEnvStat = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
	if (getEnvStat == JNI_EDETACHED) {
		if ((*vm)->AttachCurrentThread(vm, &env, &vmAttach) != 0) {
			LOG_ERROR(LOG_TAG, "playbackEndedCallback() jni: AttachCurrentThread() failed");
		}
	} else if (getEnvStat == JNI_EVERSION) {
		LOG_ERROR(LOG_TAG, "playbackEndedCallback() jni: GetEnv() unsupported version");
	}

	jmethodID methodPlaybackEndNotification = (*env)->GetMethodID(env, cls, "playbackEndNotification", "()V");
	(*env)->CallVoidMethod(env, obj, methodPlaybackEndNotification);

	(*vm)->DetachCurrentThread(vm);
}

void playbackTimestampCallback(engine_stream_context_s * stream, int64_t played) {
	JavaVM * vm = stream->engine->vm;
	jobject obj = stream->engine->obj;
	jclass cls  = stream->engine->cls;
	JNIEnv * env;

	(*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
	jmethodID methodPlaybackTimestampNotification = (*env)->GetMethodID(env, cls, "playbackUpdateTimestamp", "(J)V");
	(*env)->CallVoidMethod(env, obj, methodPlaybackTimestampNotification, (jlong) played);
}

/*
 * Class:     eu_chepy_audiokit_utils_jni_JniMediaLib
 * Method:    engineInitialize
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_engineInitialize(JNIEnv * env, jobject object) {
	JavaVM * vm;
	(*env)->GetJavaVM(env, &vm);
	jobject obj = (*env)->NewGlobalRef(env, object);
	jclass cls  = (*env)->NewGlobalRef(env, (*env)->GetObjectClass(env, obj));

	jlong engineJ = (*env)->GetLongField(env, obj, (*env)->GetFieldID(env, cls, "engineContext", "J"));
	engine_context_s * engine = id_to_ptr(engineJ);

	if (engine == NULL) {
		engine = memory_zero_alloc(sizeof(*engine));

        engine->vm = vm;
        engine->obj = obj;
        engine->cls = cls;

		if (engine_new(engine, SAMPLE_FORMAT_S16_NE, 44100, 2)) {
			LOG_ERROR(LOG_TAG, "engine_new() failure");
			goto engine_init_done_error;
		}

		if (engine_dsp_init(engine)) {
			LOG_ERROR(LOG_TAG, "engine_dsp_init() failure");
			goto engine_init_done_error;
		}
	}

	engine_set_completion_callback(engine, &playbackEndedCallback);
	engine_set_timestamp_callback(engine, &playbackTimestampCallback);

    (*env)->SetLongField(env, obj, (*env)->GetFieldID(env, cls, "engineContext", "J"), ptr_to_id(engine));

	return 0;
engine_init_done_error:
	engine_delete(engine);
	return -1;
}

/*
 * Class:     eu_chepy_audiokit_utils_jni_JniMediaLib
 * Method:    engineFinalize
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_engineFinalize(JNIEnv * env, jobject object) {
    jclass cls = (*env)->GetObjectClass(env, object);
	jlong engineJ = (*env)->GetLongField(env, object, (*env)->GetFieldID(env, cls, "engineContext", "J"));
	engine_context_s * engine = id_to_ptr(engineJ);

    if (engine != NULL) {
        jobject obj = engine->obj;
        jclass cls  = engine->cls;

        (*env)->DeleteGlobalRef(env, obj);
        (*env)->DeleteGlobalRef(env, cls);

		engine_delete(engine);
	}

	return 0;
}

/*
 * Class:     eu_chepy_audiokit_utils_jni_JniMediaLib
 * Method:    streamInitialize
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jlong JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_streamInitialize(JNIEnv * env, jobject object, jstring media_path) {
    jclass cls = (*env)->GetObjectClass(env, object);
	jlong engineJ = (*env)->GetLongField(env, object, (*env)->GetFieldID(env, cls, "engineContext", "J"));
	engine_context_s * engine = id_to_ptr(engineJ);

	engine_stream_context_s * stream = memory_alloc(sizeof *stream);

	if (engine != NULL && stream != NULL) {
		const char * stream_path = (*env)->GetStringUTFChars(env, media_path, NULL);
		if (engine_stream_new(engine, stream, stream_path) != ENGINE_OK) {
			memory_free(stream);
			return 0;
		}
		(*env)->ReleaseStringUTFChars(env, media_path, stream_path);
	}

	return ptr_to_id(stream);
}

/*
 * Class:     eu_chepy_audiokit_utils_jni_JniMediaLib
 * Method:    streamFinalize
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_streamFinalize(JNIEnv * env, jobject object, jlong context) {
	engine_stream_context_s * stream = id_to_ptr(context);

	if (stream != NULL) {
		engine_stream_delete(stream);
		memory_free(stream);
	}

	return 0;
}

/*
 * Class:     eu_chepy_audiokit_utils_jni_JniMediaLib
 * Method:    streamPreload
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_streamPreload(JNIEnv * env, jobject object, jlong context) {
	engine_stream_context_s * stream = id_to_ptr(context);

	if (stream != NULL) {
		engine_stream_preload(stream);
	}

	return 0;
}

/*
 * Class:     eu_chepy_audiokit_utils_jni_JniMediaLib
 * Method:    streamStart
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_streamStart(JNIEnv * env, jobject object, jlong context) {
	engine_stream_context_s * stream = id_to_ptr(context);

	if (stream != NULL) {
		engine_stream_start(stream);
	}

	return 0;
}

/*
 * Class:     eu_chepy_audiokit_utils_jni_JniMediaLib
 * Method:    streamStop
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_streamStop(JNIEnv * env, jobject object, jlong context) {
	engine_stream_context_s * stream = id_to_ptr(context);

	if (stream != NULL) {
		engine_stream_stop(stream);
	}

	return 0;
}

/*
 * Class:     eu_chepy_audiokit_utils_jni_JniMediaLib
 * Method:    streamSetPosition
 * Signature: (JJ)J
 */
JNIEXPORT jlong JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_streamSetPosition(JNIEnv * env, jobject object, jlong context, jlong position) {
	engine_stream_context_s * stream = id_to_ptr(context);

	if (stream != NULL) {
		engine_stream_set_position(stream, position);
	}

	return 0;
}

/*
  * Class:     eu_chepy_audiokit_utils_jni_JniMediaLib
  * Method:    streamGetPosition
  * Signature: (J)J
  */
 JNIEXPORT jlong JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_streamGetPosition(JNIEnv * env, jobject object, jlong context) {
 	engine_stream_context_s * stream = id_to_ptr(context);
    int64_t position = 0;

 	if (stream != NULL) {
 		engine_stream_get_position(stream, &position);
 	}

 	return (jlong) position;
 }

/*
 * Class:     eu_chepy_audiokit_utils_jni_JniMediaLib
 * Method:    streamGetDuration
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_streamGetDuration(JNIEnv * env, jobject object, jlong context) {
	engine_stream_context_s * stream = id_to_ptr(context);
	int64_t result = 0;

	if (stream != NULL) {
		if (engine_stream_get_duration(stream, &result) != ENGINE_OK) {
			result = 0;
		}
	}

	return result;
}

JNIEXPORT jlong JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_engineEqualizerSetEnabled(JNIEnv * env, jobject object, jboolean enabled) {
    jclass cls = (*env)->GetObjectClass(env, object);
	jlong engineJ = (*env)->GetLongField(env, object, (*env)->GetFieldID(env, cls, "engineContext", "J"));
	engine_context_s * engine = id_to_ptr(engineJ);

    if (engine != NULL) {
        engine_dsp_clear(engine, 0);
		engine_dsp_set_enabled(engine, 0, enabled ? 1 : 0);
	}

	return 0;
}

JNIEXPORT jlong JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_engineEqualizerBandSetValue(JNIEnv * env, jobject object, jint bandId, jint gain) {
    jclass cls = (*env)->GetObjectClass(env, object);
	jlong engineJ = (*env)->GetLongField(env, object, (*env)->GetFieldID(env, cls, "engineContext", "J"));
	engine_context_s * engine = id_to_ptr(engineJ);

    if (engine != NULL) {
        int _gain = (int)gain;
		engine_dsp_set_property(engine, 0, (int)bandId, &_gain);
	}

	return 0;
}

JNIEXPORT jint JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_engineEqualizerBandGetValue(JNIEnv * env, jobject object, jint bandId) {
    jclass cls = (*env)->GetObjectClass(env, object);
	jlong engineJ = (*env)->GetLongField(env, object, (*env)->GetFieldID(env, cls, "engineContext", "J"));
	engine_context_s * engine = id_to_ptr(engineJ);

    if (engine != NULL) {
        int _gain = 0;
		engine_dsp_get_property(engine, 0, (int)bandId, &_gain);
		return _gain;
	}

	return 0;
}

JNIEXPORT jboolean JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_engineEqualizerIsEnabled(JNIEnv * env, jobject object) {
    jclass cls = (*env)->GetObjectClass(env, object);
	jlong engineJ = (*env)->GetLongField(env, object, (*env)->GetFieldID(env, cls, "engineContext", "J"));
	engine_context_s * engine = id_to_ptr(engineJ);

    engine_dsp_clear(engine, 0);
    return engine_dsp_is_enabled(engine, 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_net_opusapp_player_utils_jni_JniMediaLib_engineEqualizerApplyProperties(JNIEnv * env, jobject object) {
    jclass cls = (*env)->GetObjectClass(env, object);
	jlong engineJ = (*env)->GetLongField(env, object, (*env)->GetFieldID(env, cls, "engineContext", "J"));
	engine_context_s * engine = id_to_ptr(engineJ);

    return engine_dsp_apply_properties(engine, 0) ? JNI_TRUE : JNI_FALSE;
}
