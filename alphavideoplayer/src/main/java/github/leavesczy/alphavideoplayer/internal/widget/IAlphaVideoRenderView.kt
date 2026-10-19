package github.leavesczy.alphavideoplayer.internal.widget

import android.view.ViewGroup
import github.leavesczy.alphavideoplayer.AlphaVideo
import github.leavesczy.alphavideoplayer.AlphaVideoPlayer
import github.leavesczy.alphavideoplayer.internal.render.IAlphaVideoRender

internal interface IAlphaVideoRenderView {

    fun addParentView(parentView: ViewGroup)

    fun removeParentView(parentView: ViewGroup)

    fun setLayoutParams(params: ViewGroup.LayoutParams)

    fun setVisibility(visibility: Int)

    fun bringToFront()

    fun setPlayer(player: AlphaVideoPlayer)

    fun setVideoRenderer(renderer: IAlphaVideoRender)

    fun setScaleType(scaleType: AlphaVideo.ScaleType)

    fun getScaleType(): AlphaVideo.ScaleType

    fun setLayout(layout: AlphaVideo.Layout)

    fun getLayout(): AlphaVideo.Layout

    fun isSurfaceCreated(): Boolean

    fun getMeasuredWidth(): Int

    fun getMeasuredHeight(): Int

    fun measureInternal(videoWidth: Float, videoHeight: Float)

    fun requestRender()

    fun onFirstFrame()

    fun onCompletion()

    /** Stops sampling; keeps geometry (temporary surface loss). */
    fun onSurfaceLost()

    fun onPause()

    fun release()

    fun fitDecoderBuffer(sourceWidth: Int, sourceHeight: Int): Boolean

}
