package com.xyq.librender.model

import android.opengl.GLES30
import android.opengl.Matrix
import com.xyq.librender.utils.OpenGLTools

/**
 * RGBA format
 */
class FboDesc(
    private var mWidth: Int = -1,
    private var mHeight: Int = -1
) {

    private var mFboId: Int = -1
    private var mFboTextureId: Int = -1
    private var mRotate = 0
    private var mFBOMatrix: FloatArray = FloatArray(16)

    init {
        mFboId = OpenGLTools.createFBO()
        mFboTextureId = OpenGLTools.createFBOTexture(mFboId, mWidth, mHeight)

        Matrix.setIdentityM(mFBOMatrix, 0)
        Matrix.scaleM(mFBOMatrix, 0, 1f, -1f, 1f)
    }

    fun updateSize(width: Int, height: Int) {
        if (mWidth != width || mHeight != height) {
            mWidth = width
            mHeight = height
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mFboTextureId)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height,
                0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NONE)
        }
    }

    fun updateRotate(rotate: Int) {
        if (rotate != mRotate) {
            mRotate = rotate
            Matrix.setIdentityM(mFBOMatrix, 0)
            if (rotate != 0) {
                Matrix.rotateM(mFBOMatrix, 0, rotate.toFloat(), 0f, 0f, 1f)
            }
            Matrix.scaleM(mFBOMatrix, 0, 1f, -1f, 1f)
        }
    }

    fun isValid(): Boolean {
        return mFboId >= 0 && mFboTextureId >= 0 && mWidth >= 0 && mHeight >= 0
    }

    fun bind() {
        OpenGLTools.bindFBO(mFboId)
    }

    fun unBind() {
        OpenGLTools.unbindFBO()
    }

    fun getTextureId(): Int {
        return mFboTextureId
    }

    fun getMatrix(): FloatArray {
        return mFBOMatrix
    }

    fun release() {
        OpenGLTools.deleteFBO(mFboId, mFboTextureId)
    }

    override fun toString(): String {
        return "FboDesc(mWidth=$mWidth, mHeight=$mHeight, mFboId=$mFboId, mFboTextureId=$mFboTextureId, mRotate=$mRotate)"
    }


}