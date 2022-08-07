#include "FFMpegPlayer.h"

FFMpegPlayer::FFMpegPlayer() {
    LOGI("FFMpegPlayer")
    pthread_mutex_init(&mMutex, nullptr);
    pthread_cond_init(&mCond, nullptr);
}

FFMpegPlayer::~FFMpegPlayer() {
    pthread_mutex_destroy(&mMutex);
    pthread_cond_destroy(&mCond);
    LOGI("~FFMpegPlayer")
}

bool FFMpegPlayer::prepare(JNIEnv *env, std::string &path, jobject surface) {
    // step0: register jvm to ffmpeg for mediacodec decoding
    JavaVM *javaVm = nullptr;
    env->GetJavaVM(&javaVm);
    if (javaVm != nullptr) {
        av_jni_set_java_vm(javaVm, nullptr);
    }

    // step1: alloc format context
    mFtx = avformat_alloc_context();

    // step2: open input file
    int ret = avformat_open_input(&mFtx, path.c_str(), nullptr, nullptr);
    if (ret < 0) {
        env->CallVoidMethod(jPlayerListenerObj, onCallError, ret, env->NewStringUTF(av_err2str(ret)));
        return false;
    }

    // step3: find video stream index
    avformat_find_stream_info(mFtx, nullptr);
    int videoIndex = -1;
    for (int i = 0; i < mFtx->nb_streams; i++) {
        if (mFtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoIndex = i;
            break;
        }
    }

    if (videoIndex < 0) {
        std::string msg = "not find video stream";
        env->CallVoidMethod(jPlayerListenerObj, onCallError, ret, env->NewStringUTF(msg.c_str()));
        return false;
    }

    // step4: prepare video decoder
    mVideoDecoder = new VideoDecoder(videoIndex, mFtx);
    mVideoDecoder->setErrorMsgListener([this, env](int err, std::string &msg) {
        env->CallVoidMethod(jPlayerListenerObj, onCallError, err, env->NewStringUTF(msg.c_str()));
    });
    mVideoDecoder->setOnFrameArrived([this, env](AVFrame *frame) {
        sync(frame);
        doRender(env, frame);
    });

    bool prepared = mVideoDecoder->prepare(mFtx->streams[videoIndex]->codecpar, surface);
    mIsRunning = prepared;

    env->CallVoidMethod(jPlayerListenerObj, onCallPrepared, mVideoDecoder->getWidth(), mVideoDecoder->getHeight());
    return prepared;
}

void FFMpegPlayer::start(JNIEnv *env) {
    AVPacket *avPacket = av_packet_alloc();
    while (mIsRunning && mVideoDecoder) {
        int ret = av_read_frame(mFtx, avPacket);
        if (ret != 0) {
            LOGE("read packet...end")
            av_packet_unref(avPacket);
            break;
        }

        if (avPacket->stream_index != mVideoDecoder->getStreamIndex()) {
            LOGI("read packet...other")
            av_packet_unref(avPacket);
            continue;
        }

        mVideoDecoder->decode(avPacket);
        av_packet_unref(avPacket);
    }
    av_packet_free(&avPacket);
    av_freep(&avPacket);

    if (mIsRunning) {
        LOGI("decode...end")
        env->CallVoidMethod(jPlayerListenerObj, onCallCompleted);
        mIsRunning = false;
    } else {
        LOGI("decode...abort")
    }

    pthread_mutex_lock(&mMutex);
    pthread_cond_signal(&mCond);
    pthread_mutex_unlock(&mMutex);
}

void FFMpegPlayer::sync(AVFrame *avFrame) {
    if (mVideoDecoder == nullptr) {
        return;
    }
    auto pts = avFrame->best_effort_timestamp;
    if (pts == AV_NOPTS_VALUE) {
        pts = 0;
        LOGE("AV_NOPTS_VALUE")
    }
    // s -> us
    pts = pts * av_q2d(mFtx->streams[mVideoDecoder->getStreamIndex()]->time_base) * 1000 * 1000;
    int64_t elapsedTime;
    if (mStartTime < 0) {
        mStartTime = av_gettime();
        elapsedTime = 0;
    } else {
        elapsedTime = av_gettime() - mStartTime;
    }
    int64_t diff = pts - elapsedTime;
    LOGI("video frame arrived, pts: %" PRId64 ", elapsedTime: %" PRId64 " diff: %" PRId64, pts, elapsedTime, diff)
    if (diff > 0) {
        av_usleep(diff);
    }
}

void FFMpegPlayer::doRender(JNIEnv *env, AVFrame *avFrame) {
    if (avFrame->format == AV_PIX_FMT_YUV420P) {
        if (!avFrame->data[0] || !avFrame->data[1] || !avFrame->data[2]) {
            LOGE("doRender failed, no yuv buffer")
            return;
        }

        int ySize = avFrame->width * avFrame->height;
        auto y = env->NewByteArray(ySize);
        env->SetByteArrayRegion(y, 0, ySize, reinterpret_cast<const jbyte *>(avFrame->data[0]));
        auto u = env->NewByteArray(ySize / 4);
        env->SetByteArrayRegion(u, 0, ySize / 4, reinterpret_cast<const jbyte *>(avFrame->data[1]));
        auto v = env->NewByteArray(ySize / 4);
        env->SetByteArrayRegion(v, 0, ySize / 4, reinterpret_cast<const jbyte *>(avFrame->data[2]));

        env->CallVoidMethod(jPlayerListenerObj, onFrameArrived,
                            avFrame->width, avFrame->height, y, u, v);

        env->DeleteLocalRef(y);
        env->DeleteLocalRef(u);
        env->DeleteLocalRef(v);
    } else if (avFrame->format == AV_PIX_FMT_NV12) {
        if (!avFrame->data[0] || !avFrame->data[1]) {
            LOGE("doRender failed, no nv21 buffer")
            return;
        }

        int ySize = avFrame->width * avFrame->height;
        auto y = env->NewByteArray(ySize);
        env->SetByteArrayRegion(y, 0, ySize, reinterpret_cast<const jbyte *>(avFrame->data[0]));
        auto uv = env->NewByteArray(ySize / 2);
        env->SetByteArrayRegion(uv, 0, ySize / 2, reinterpret_cast<const jbyte *>(avFrame->data[1]));

        env->CallVoidMethod(jPlayerListenerObj, onFrameArrived,
                            avFrame->width, avFrame->height, y, uv, nullptr);

        env->DeleteLocalRef(y);
        env->DeleteLocalRef(uv);
    } else if (avFrame->format == AV_PIX_FMT_MEDIACODEC) {
        av_mediacodec_release_buffer((AVMediaCodecBuffer *)avFrame->data[3], 1);
    }
}

void FFMpegPlayer::stop() {
    LOGE("stop, mIsRunning: %d", mIsRunning)
    if (mIsRunning) {
        mIsRunning = false;
        pthread_mutex_lock(&mMutex);
        pthread_cond_wait(&mCond, &mMutex);
        pthread_mutex_unlock(&mMutex);
    }
    mStartTime = -1;
    LOGE("stop done")

    if (mVideoDecoder != nullptr) {
        delete mVideoDecoder;
        mVideoDecoder = nullptr;
    }

    if (mFtx != nullptr) {
        avformat_close_input(&mFtx);
        avformat_free_context(mFtx);
        mFtx = nullptr;
        LOGI("format context...release")
    }
}

void FFMpegPlayer::registerPlayerListener(JNIEnv *env, jobject listener) {
    if (listener != nullptr) {
        jclass jclazz = env->GetObjectClass(listener);
        if (!jclazz) {
            return;
        }

        jPlayerListenerObj = env->NewGlobalRef(listener);

        onCallPrepared = env->GetMethodID(jclazz, "onPrepared", "(II)V");
        onCallError = env->GetMethodID(jclazz, "onError", "(ILjava/lang/String;)V");
        onCallCompleted = env->GetMethodID(jclazz, "onCompleted", "()V");
        onFrameArrived = env->GetMethodID(jclazz, "onFrameArrived", "(II[B[B[B)V");
    } else {
        if (jPlayerListenerObj != nullptr) {
            env->DeleteGlobalRef(jPlayerListenerObj);
            jPlayerListenerObj = nullptr;
        }
        onCallPrepared = nullptr;
        onCallError = nullptr;
        onCallCompleted = nullptr;
        onFrameArrived = nullptr;
    }
}
