package github.leavesczy.alphavideoplayer.internal.render

import github.leavesczy.alphavideoplayer.AlphaVideo

/** Builds vertex attributes (NDC + RGB/alpha UVs); the shader applies the SurfaceTexture ST matrix. */
internal object AlphaVideoTextureCropUtil {

    fun calculateVerticesData(
        layout: AlphaVideo.Layout,
        scaleType: AlphaVideo.ScaleType,
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Float,
        videoHeight: Float,
        textureWidth: Float,
        textureHeight: Float
    ): FloatArray {
        var videoWidth = videoWidth
        var videoHeight = videoHeight
        val currentRatio = viewWidth / viewHeight
        val videoRatio = videoWidth / videoHeight
        val ratioX: Float
        val ratioY: Float
        if (currentRatio > videoRatio) {
            videoWidth = viewWidth
            videoHeight = videoWidth / videoRatio
            ratioX = 0f
            ratioY = (1 - (viewHeight / videoHeight)) / 2
        } else {
            videoHeight = viewHeight
            videoWidth = videoHeight * videoRatio
            ratioX = (1 - (viewWidth / videoWidth)) / 2
            ratioY = 0f
        }
        return when (scaleType) {
            AlphaVideo.ScaleType.FitCenter,
            AlphaVideo.ScaleType.FitTop,
            AlphaVideo.ScaleType.FitBottom -> {
                val fitRatioX: Float
                val letterboxY: Float
                if (currentRatio > videoRatio) {
                    val fittedWidth = viewHeight * videoRatio
                    fitRatioX = (1 - (fittedWidth / viewWidth)) / 2
                    letterboxY = 0f
                } else {
                    val fittedHeight = viewWidth / videoRatio
                    fitRatioX = 0f
                    letterboxY = 1f - (fittedHeight / viewHeight)
                }
                val topZoomRatio: Float
                val bottomZoomRatio: Float
                when (scaleType) {
                    AlphaVideo.ScaleType.FitTop -> {
                        topZoomRatio = 0f
                        bottomZoomRatio = letterboxY
                    }

                    AlphaVideo.ScaleType.FitBottom -> {
                        topZoomRatio = letterboxY
                        bottomZoomRatio = 0f
                    }

                    else -> {
                        topZoomRatio = letterboxY / 2f
                        bottomZoomRatio = letterboxY / 2f
                    }
                }
                getZoomData(
                    layout = layout,
                    leftZoomRatio = fitRatioX,
                    topZoomRatio = topZoomRatio,
                    rightZoomRatio = fitRatioX,
                    bottomZoomRatio = bottomZoomRatio,
                    textureWidth = textureWidth,
                    textureHeight = textureHeight
                )
            }

            AlphaVideo.ScaleType.FitWidth -> {
                val displayHeight = viewWidth / videoRatio
                if (displayHeight > viewHeight) {
                    val cropY = (1f - viewHeight / displayHeight) / 2f
                    getCropData(
                        layout = layout,
                        leftCropRatio = 0f,
                        topCropRatio = cropY,
                        rightCropRatio = 0f,
                        bottomCropRatio = cropY,
                        textureWidth = textureWidth,
                        textureHeight = textureHeight
                    )
                } else {
                    val letterboxY = (1f - displayHeight / viewHeight) / 2f
                    getZoomData(
                        layout = layout,
                        leftZoomRatio = 0f,
                        topZoomRatio = letterboxY,
                        rightZoomRatio = 0f,
                        bottomZoomRatio = letterboxY,
                        textureWidth = textureWidth,
                        textureHeight = textureHeight
                    )
                }
            }

            AlphaVideo.ScaleType.CenterCrop -> {
                getCropData(
                    layout = layout,
                    leftCropRatio = ratioX,
                    topCropRatio = ratioY,
                    rightCropRatio = ratioX,
                    bottomCropRatio = ratioY,
                    textureWidth = textureWidth,
                    textureHeight = textureHeight
                )
            }
        }
    }

    private data class UvRect(
        val u0: Float,
        val v0: Float,
        val u1: Float,
        val v1: Float
    )

    private fun fullHalfUv(layout: AlphaVideo.Layout, isAlpha: Boolean): UvRect {
        return when (layout) {
            AlphaVideo.Layout.AlphaLeft -> {
                if (isAlpha) UvRect(u0 = 0f, v0 = 0f, u1 = 0.5f, v1 = 1f)
                else UvRect(u0 = 0.5f, v0 = 0f, u1 = 1f, v1 = 1f)
            }

            AlphaVideo.Layout.AlphaRight -> {
                if (isAlpha) UvRect(u0 = 0.5f, v0 = 0f, u1 = 1f, v1 = 1f)
                else UvRect(u0 = 0f, v0 = 0f, u1 = 0.5f, v1 = 1f)
            }

            AlphaVideo.Layout.AlphaTop -> {
                if (isAlpha) UvRect(u0 = 0f, v0 = 0.5f, u1 = 1f, v1 = 1f)
                else UvRect(u0 = 0f, v0 = 0f, u1 = 1f, v1 = 0.5f)
            }

            AlphaVideo.Layout.AlphaBottom -> {
                if (isAlpha) UvRect(u0 = 0f, v0 = 0f, u1 = 1f, v1 = 0.5f)
                else UvRect(u0 = 0f, v0 = 0.5f, u1 = 1f, v1 = 1f)
            }
        }
    }

    private fun croppedHalfUv(
        layout: AlphaVideo.Layout,
        isAlpha: Boolean,
        leftCropRatio: Float,
        topCropRatio: Float,
        rightCropRatio: Float,
        bottomCropRatio: Float
    ): UvRect {
        val full = fullHalfUv(layout = layout, isAlpha = isAlpha)
        val uSpan = full.u1 - full.u0
        val vSpan = full.v1 - full.v0
        return UvRect(
            u0 = full.u0 + leftCropRatio * uSpan,
            v0 = full.v0 + bottomCropRatio * vSpan,
            u1 = full.u1 - rightCropRatio * uSpan,
            v1 = full.v1 - topCropRatio * vSpan
        )
    }

    /** Keeps GL_LINEAR from sampling across the RGB/alpha split or the frame edge. */
    private fun insetHalfTexel(
        rect: UvRect,
        textureWidth: Float,
        textureHeight: Float
    ): UvRect {
        if (textureWidth < 2f || textureHeight < 2f) {
            return rect
        }
        val halfU = 0.5f / textureWidth
        val halfV = 0.5f / textureHeight
        val u0 = rect.u0 + halfU
        val v0 = rect.v0 + halfV
        val u1 = rect.u1 - halfU
        val v1 = rect.v1 - halfV
        if (u0 >= u1 || v0 >= v1) {
            // The inset would invert this rect.
            return rect
        }
        return UvRect(u0 = u0, v0 = v0, u1 = u1, v1 = v1)
    }

    private fun getZoomData(
        layout: AlphaVideo.Layout,
        leftZoomRatio: Float,
        topZoomRatio: Float,
        rightZoomRatio: Float,
        bottomZoomRatio: Float,
        textureWidth: Float,
        textureHeight: Float
    ): FloatArray {
        val color = insetHalfTexel(
            rect = fullHalfUv(layout = layout, isAlpha = false),
            textureWidth = textureWidth,
            textureHeight = textureHeight
        )
        val alpha = insetHalfTexel(
            rect = fullHalfUv(layout = layout, isAlpha = true),
            textureWidth = textureWidth,
            textureHeight = textureHeight
        )
        return floatArrayOf(
            -1.0f + leftZoomRatio * 2, -1.0f + bottomZoomRatio * 2, 0f,
            color.u0, color.v0, alpha.u0, alpha.v0,
            1.0f - rightZoomRatio * 2, -1.0f + bottomZoomRatio * 2, 0f,
            color.u1, color.v0, alpha.u1, alpha.v0,
            -1.0f + leftZoomRatio * 2, 1.0f - topZoomRatio * 2, 0f,
            color.u0, color.v1, alpha.u0, alpha.v1,
            1.0f - rightZoomRatio * 2, 1.0f - topZoomRatio * 2, 0f,
            color.u1, color.v1, alpha.u1, alpha.v1
        )
    }

    private fun getCropData(
        layout: AlphaVideo.Layout,
        leftCropRatio: Float,
        topCropRatio: Float,
        rightCropRatio: Float,
        bottomCropRatio: Float,
        textureWidth: Float,
        textureHeight: Float
    ): FloatArray {
        val color = insetHalfTexel(
            rect = croppedHalfUv(
                layout = layout,
                isAlpha = false,
                leftCropRatio = leftCropRatio,
                topCropRatio = topCropRatio,
                rightCropRatio = rightCropRatio,
                bottomCropRatio = bottomCropRatio
            ),
            textureWidth = textureWidth,
            textureHeight = textureHeight
        )
        val alpha = insetHalfTexel(
            rect = croppedHalfUv(
                layout = layout,
                isAlpha = true,
                leftCropRatio = leftCropRatio,
                topCropRatio = topCropRatio,
                rightCropRatio = rightCropRatio,
                bottomCropRatio = bottomCropRatio
            ),
            textureWidth = textureWidth,
            textureHeight = textureHeight
        )
        return floatArrayOf(
            -1.0f, -1.0f, 0f, color.u0, color.v0, alpha.u0, alpha.v0,
            1.0f, -1.0f, 0f, color.u1, color.v0, alpha.u1, alpha.v0,
            -1.0f, 1.0f, 0f, color.u0, color.v1, alpha.u0, alpha.v1,
            1.0f, 1.0f, 0f, color.u1, color.v1, alpha.u1, alpha.v1
        )
    }

}
