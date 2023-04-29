package com.xyq.ffmpegdemo.render.core

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.util.Size
import com.xyq.ffmpegdemo.render.model.RenderData
import com.xyq.ffmpegdemo.render.ResManager
import com.xyq.ffmpegdemo.render.model.FboDesc
import com.xyq.ffmpegdemo.render.utils.OpenGLTools
import com.xyq.ffmpegdemo.render.utils.ShaderHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

abstract class BaseDrawer(private val mContext: Context) : IDrawer {

    companion object {
        private const val TAG = "BaseDrawer"
    }

    protected var mProgram = -1

    protected var mTextures: IntArray? = null

    private var mRenderData: RenderData? = null

    private val mVertexCoors = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )

    private val mTextureCoors = floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    )

    private var mInit = false
    private var mInitRunnable: Runnable? = null

    private var mWorldWidth = -1
    private var mWorldHeight = -1

    private var mVideoWidth = -1
    private var mVideoHeight = -1

    private var mSizeChanged = false

    private var mWidthRatio = 1f
    private var mHeightRatio = 1f

    private var mMatrix: FloatArray? = null
    private var mFBOMatrix: FloatArray = FloatArray(16)

    // fbo
    private var mFboDesc: FboDesc? = null

    private var mVertexPosHandler = -1

    private var mTexturePosHandler = -1

    private var mVertexMatrixHandler = -1

    private var mVertexBuffer: FloatBuffer

    private var mTextureBuffer: FloatBuffer

    init {
        var byteBuffer = ByteBuffer.allocateDirect(mVertexCoors.size * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        mVertexBuffer = byteBuffer.asFloatBuffer()
        mVertexBuffer.put(mVertexCoors)
        mVertexBuffer.position(0)

        byteBuffer = ByteBuffer.allocateDirect(mTextureCoors.size * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        mTextureBuffer = byteBuffer.asFloatBuffer()
        mTextureBuffer.put(mTextureCoors)
        mTextureBuffer.position(0)

        Matrix.setIdentityM(mFBOMatrix, 0)
        Matrix.scaleM(mFBOMatrix, 0, 1f, -1f, 1f)
    }

    override fun setVideoSize(w: Int, h: Int) {
        if (mVideoWidth != w || mVideoHeight != h) {
            mVideoWidth = w
            mVideoHeight = h
            mSizeChanged = true
            Log.i(TAG, "setVideoSize: $w x $h")
        }
    }

    override fun getVideoSize(): Size {
        return Size(mVideoWidth, mVideoHeight)
    }

    override fun setWorldSize(w: Int, h: Int) {
        if (mWorldWidth != w || mWorldHeight != h) {
            mWorldWidth = w
            mWorldHeight = h
            mSizeChanged = true
            Log.i(TAG, "setWorldSize: $w x $h")
        }
    }

    @Synchronized
    override fun pushData(data: RenderData) {
        mRenderData = data
    }

    abstract fun getVertexShader(): Int

    abstract fun getFragmentShader(): Int

    abstract fun getTextureSize(): Int

    abstract fun onInitParam()

    abstract fun uploadData(textures: IntArray, data: RenderData?)

    open fun onRelease() {}

    open fun getTextureType(): Int {
        return GLES20.GL_TEXTURE_2D
    }

    override fun init(async: Boolean) {
        Log.i(TAG, "init async: $async")
        mInitRunnable = Runnable {
            val vertexShader = ResManager.ShaderCache.findVertexShader(getVertexShader(), mContext)
            val fragmentShader = ResManager.ShaderCache.findFragmentShader(getFragmentShader(), mContext)
            mProgram = ShaderHelper.buildProgram(vertexShader, fragmentShader)

            mVertexPosHandler = GLES20.glGetAttribLocation(mProgram, "aPosition")
            mTexturePosHandler = GLES20.glGetAttribLocation(mProgram, "aCoordinate")
            mVertexMatrixHandler = GLES20.glGetUniformLocation(mProgram, "uMatrix")

            mTextures = OpenGLTools.createTextureIds(getTextureSize())
            for (texture in mTextures!!) {
                val textureType = getTextureType()
                GLES20.glBindTexture(textureType, texture)
                GLES20.glTexParameterf(textureType, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
                GLES20.glTexParameterf(textureType, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
                GLES20.glTexParameteri(textureType, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(textureType, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, GLES20.GL_NONE)

            onInitParam()

            mInit = true
            Log.i(TAG, "init async: $async end")
        }
        if (!async) {
            mInitRunnable?.run()
            mInitRunnable = null
        }
    }

    override fun draw() {
        pendingTaskRun()
        GLES20.glViewport(0, 0, mWorldWidth ,mWorldHeight)
        drawCore(mTextures, true, mMatrix)
    }

    override fun draw(input: Int) {
        pendingTaskRun()
        GLES20.glViewport(0, 0, mWorldWidth ,mWorldHeight)
        val textures = IntArray(1)
        textures[0] = input
        drawCore(textures, false, mMatrix)
    }

    override fun drawToFbo(): Int {
        pendingTaskRun()
        return drawToFbo(mTextures, true)
    }

    override fun drawToFbo(input: Int): Int {
        pendingTaskRun()
        val textures = IntArray(1)
        textures[0] = input
        return drawToFbo(textures, false)
    }

    private fun drawToFbo(inputs: IntArray?, useBufferInput: Boolean): Int {
        if (mFboDesc == null && mVideoWidth > 0 && mVideoHeight > 0) {
            Log.i(TAG, "drawToFbo: create fbo")
            mFboDesc = FboDesc(mVideoWidth, mVideoHeight)
        }

        if (mFboDesc == null || !mFboDesc!!.isValid()) {
            Log.i(TAG, "drawToFbo: fbo not ready")
            return -1
        }

        mFboDesc!!.bind()
        mFboDesc!!.updateSize(mVideoWidth, mVideoHeight)
        GLES20.glViewport(0, 0, mVideoWidth ,mVideoHeight)
        drawCore(inputs, useBufferInput, mFBOMatrix)
        mFboDesc!!.unBind()

        return mFboDesc!!.getTextureId()
    }

    override fun release() {
        GLES20.glDisableVertexAttribArray(mVertexPosHandler)
        GLES20.glDisableVertexAttribArray(mTexturePosHandler)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, GLES20.GL_NONE)
        // delete texture
        mTextures?.apply {
            GLES20.glDeleteTextures(getTextureSize(), mTextures, 0)
            mTextures = null
        }

        mFboDesc?.let {
            it.release()
            mFboDesc = null
        }
        // sub release
        onRelease()
        GLES20.glDeleteProgram(mProgram)
    }

    private fun initDefMatrix() {
        if (mMatrix != null && !mSizeChanged) return
        if (mVideoWidth != -1 && mVideoHeight != -1 && mWorldWidth != -1 && mWorldHeight != -1) {
            mMatrix = FloatArray(16)
            val projMatrix = FloatArray(16)
            val originRatio = mVideoWidth / mVideoHeight.toFloat()
            val worldRatio = mWorldWidth / mWorldHeight.toFloat()
            if (originRatio > worldRatio) {
                val actualRatio = originRatio / worldRatio
                Matrix.orthoM(projMatrix, 0, -1f, 1f, -actualRatio, actualRatio, 3f, 5f)
                mHeightRatio = actualRatio
            } else {
                val actualRatio = worldRatio / originRatio
                Matrix.orthoM(projMatrix, 0, -actualRatio, actualRatio, -1f, 1f, 3f, 5f)
                mWidthRatio = actualRatio
            }
            val viewMatrix = FloatArray(16)
            Matrix.setLookAtM(viewMatrix, 0,
                0f, 0f, 5.0f,
                0f, 0f, 0f,
                0f, 1.0f, 0f
            )
            Matrix.multiplyMM(mMatrix, 0, projMatrix, 0, viewMatrix, 0)
        }
    }

    private fun pendingTaskRun() {
        mInitRunnable?.let {
            it.run()
            mInitRunnable = null
        }

        initDefMatrix()
    }

    private fun drawCore(textures: IntArray?, useBufferInput: Boolean, matrix: FloatArray?) {
        if (!mInit) {
            Log.e(TAG, "drawCore: not init")
            return
        }

        if (matrix == null) {
            Log.e(TAG, "drawCore: matrix not ready")
            return
        }

        GLES20.glClearColor(23f / 255, 23f / 255, 23f / 255, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(mProgram)

        GLES20.glEnableVertexAttribArray(mVertexPosHandler)
        GLES20.glEnableVertexAttribArray(mTexturePosHandler)

        GLES20.glUniformMatrix4fv(mVertexMatrixHandler, 1, false, matrix, 0)

        // x,y -> size is 2
        GLES20.glVertexAttribPointer(mVertexPosHandler, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer)
        GLES20.glVertexAttribPointer(mTexturePosHandler, 2, GLES20.GL_FLOAT, false, 0, mTextureBuffer)

        if (useBufferInput) {
            synchronized(this) {
                uploadData(textures!!, mRenderData)
                mRenderData = null
            }
        } else {
            uploadData(textures!!, null)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}